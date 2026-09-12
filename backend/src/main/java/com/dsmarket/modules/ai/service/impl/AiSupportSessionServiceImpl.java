package com.dsmarket.modules.ai.service.impl;

import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.ai.config.AiProperties;
import com.dsmarket.modules.ai.dto.AiSupportMessageVO;
import com.dsmarket.modules.ai.dto.AiSupportSessionVO;
import com.dsmarket.modules.ai.dto.SupportRequestResult;
import com.dsmarket.modules.ai.dto.SupportSendResult;
import com.dsmarket.modules.ai.entity.AiSupportMessage;
import com.dsmarket.modules.ai.entity.AiSupportSession;
import com.dsmarket.modules.ai.enums.AiSupportOrigin;
import com.dsmarket.modules.ai.enums.AiSupportSender;
import com.dsmarket.modules.ai.enums.AiSupportSessionStatus;
import com.dsmarket.modules.ai.limit.SupportRateLimiter;
import com.dsmarket.modules.ai.mapper.AiSupportMessageMapper;
import com.dsmarket.modules.ai.mapper.AiSupportSessionMapper;
import com.dsmarket.modules.ai.service.AiSupportSessionService;
import com.dsmarket.modules.ai.session.SupportDedupStore;
import com.dsmarket.modules.ai.support.AfterCommit;
import com.dsmarket.modules.ai.support.SupportEventPublisher;
import com.dsmarket.modules.ai.support.SupportEvents;
import com.dsmarket.modules.ai.support.SupportSeatPresence;
import com.dsmarket.modules.ai.support.SupportUserMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 人工客服会话服务实现（C4 买家侧闭环：切片 1 建状态机，切片 2 接实时推送）。
 *
 * <p>依赖里<b>没有</b>任何 LLM/检索/问题池组件 —— 人工态不经模型是结构保证（见接口注释）。</p>
 *
 * <p>切片 2 起坐席接缝由 {@code RedisSupportSeatPresence}（心跳驱动）实现，在线分支真正生效：
 * 有坐席在线时 request 返回 pending_human 并等待接入，无人在线时才走诚实留言降级。</p>
 *
 * <p><b>推送一律 after-commit</b>：本类的方法在事务里改库，事件若在提交前发出，
 * 一旦回滚买家就会看到库里并不存在的消息（见 {@link AfterCommit}）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSupportSessionServiceImpl implements AiSupportSessionService {

    // ------------------------------------------------------------ 对外话术常量（前端原样展示）

    /** 无坐席在线时的提示（§4.1 步骤 4；措辞即"不谎报有人接"底线的落点） */
    public static final String TIP_OFFLINE = "当前暂无客服在线，请留言，我们会尽快回复。";
    /** 有坐席在线、等待接入时的提示 */
    public static final String TIP_PENDING = "正在为您接入人工客服…";
    /** 幂等复用已有会话时的提示（已在留言态，前端应提示可继续追加） */
    public static final String TIP_EXISTING_MESSAGE_LEFT = "您有未处理的人工留言，可继续补充。";
    /** 幂等复用已有会话时的提示（人工处理中） */
    public static final String TIP_EXISTING_HUMAN_ACTIVE = "人工客服正在为您服务。";

    // ------------------------------------------------------------ 错误话术常量

    /** H18：closed 后发消息 */
    public static final String SESSION_CLOSED_MESSAGE = "本次人工会话已结束，可重新发起转人工。";
    /** 无任何人工会话时发消息 */
    public static final String NO_ACTIVE_SESSION_MESSAGE = "当前没有进行中的人工会话，请先发起转人工。";
    /** §4.4 限流 */
    public static final String SUPPORT_RATE_LIMITED_MESSAGE = "发送消息太频繁，请稍后再试。";
    /** trim 后为空 */
    public static final String EMPTY_CONTENT_MESSAGE = "消息内容不能为空。";
    /** 非法 origin（含买家自报 ANCHOR_HIT） */
    public static final String INVALID_ORIGIN_MESSAGE = "非法的转人工触发途径。";

    private final AiSupportSessionMapper sessionMapper;
    private final AiSupportMessageMapper messageMapper;
    private final SupportRateLimiter rateLimiter;
    private final SupportDedupStore dedupStore;
    private final SupportSeatPresence seatPresence;
    private final SupportEventPublisher publisher;
    private final SupportUserMasker userMasker;
    private final AiProperties properties;

    // ------------------------------------------------------------ C4-F1 转人工

    @Override
    public SupportRequestResult request(Long userId, String origin) {
        return doRequest(userId, normalizePublicOrigin(origin));
    }

    @Override
    public SupportRequestResult requestByAnchor(Long userId) {
        // 服务端内部途径：不经 normalizePublicOrigin，故 origin 只可能是 ANCHOR_HIT（§4.1 入口分流②）
        return doRequest(userId, AiSupportOrigin.ANCHOR_HIT_VALUE);
    }

    @Override
    public boolean hasHumanTakeover(Long userId) {
        // selectActiveByUser 的 WHERE 已限定 status IN PH，此处再判一次是防御性重复：
        // 该 SQL 与 V8 部分唯一索引的 WHERE 子句必须同口径，将来若有人放宽 SQL，这行能兜住。
        AiSupportSession active = sessionMapper.selectActiveByUser(userId);
        return active != null && AiSupportSessionStatus.isHumanTakeover(active.getStatus());
    }

    /** request 与 requestByAnchor 的共同实现；差别只在 origin 的来源与校验方式 */
    private SupportRequestResult doRequest(Long userId, String normalizedOrigin) {
        // 幂等（§4.1 步骤 3）：已有 PH 会话 → 返回现状，不新建（H7）；复用时同样按当前坐席在线态给提示
        AiSupportSession existing = sessionMapper.selectActiveByUser(userId);
        if (existing != null) {
            log.info("[ai][c4] 转人工幂等复用：userId={} sessionId={} status={}",
                    userId, existing.getId(), existing.getStatus());
            return toRequestResult(existing, anySeatOnline());
        }

        AiSupportSession session = new AiSupportSession();
        session.setUserId(userId);
        session.setStatus(AiSupportSessionStatus.PENDING_HUMAN_VALUE);
        session.setOrigin(normalizedOrigin);
        session.setRequestedAt(LocalDateTime.now());
        try {
            sessionMapper.insert(session);
        } catch (DuplicateKeyException e) {
            // 并发兜底：两个 request 同时"查无 → 新建"，唯一索引 uk_ai_support_session_active_user
            // 只放行一个；落败方回查活跃会话按幂等返回（H7），不把冲突抛给买家
            AiSupportSession winner = sessionMapper.selectActiveByUser(userId);
            if (winner == null) {
                // 极端：冲突后竟查不到活跃会话（对方会话已被同瞬间 closed）→ 如实上抛，不假装成功
                log.warn("[ai][c4] 转人工唯一冲突后回查不到活跃会话. userId={}", userId);
                throw e;
            }
            log.info("[ai][c4] 转人工并发冲突，幂等回退到已有会话. userId={} sessionId={}",
                    userId, winner.getId());
            return toRequestResult(winner, anySeatOnline());
        }
        log.info("[ai][c4] 转人工会话已建. userId={} sessionId={} origin={}",
                userId, session.getId(), normalizedOrigin);
        publishSessionNew(session);
        return toRequestResult(session, anySeatOnline());
    }

    // ------------------------------------------------------------ C4-F4 人工消息

    @Override
    @Transactional
    public SupportSendResult sendMessage(Long userId, String content, String clientMsgId) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), EMPTY_CONTENT_MESSAGE);
        }
        int max = properties.getSupport().getUserContentMax();
        if (text.length() > max) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "消息过长（最多 " + max + " 字）。");
        }

        AiSupportSession session = sessionMapper.selectLatestByUser(userId);
        if (session == null) {
            throw new BusinessException(ErrorCode.CONFLICT.getCode(), NO_ACTIVE_SESSION_MESSAGE);
        }
        if (AiSupportSessionStatus.CLOSED_VALUE.equals(session.getStatus())) {
            // H18：closed 后一律拒绝且不落库（前端据此禁用输入框并引导重新发起）
            log.info("[ai][c4] closed 会话拒绝发消息. userId={} sessionId={}", userId, session.getId());
            throw new BusinessException(ErrorCode.CONFLICT.getCode(), SESSION_CLOSED_MESSAGE);
        }
        if (!AiSupportSessionStatus.isHumanTakeover(session.getStatus())) {
            // 理论不可达（ai_active 不落库，PH/closed 已穷举）——防御性拒绝而非默默写入
            log.warn("[ai][c4] 会话状态不在 PH 集合，拒绝发消息. userId={} sessionId={} status={}",
                    userId, session.getId(), session.getStatus());
            throw new BusinessException(ErrorCode.CONFLICT.getCode(), NO_ACTIVE_SESSION_MESSAGE);
        }

        // 幂等（§4.4 P6）：命中 → 200 且不重落库、不消耗限流额度
        if (isNotBlank(clientMsgId)) {
            Long prior = dedupStore.findMessageId(userId, session.getId(), clientMsgId);
            if (prior != null) {
                log.info("[ai][c4] 人工态消息幂等命中. userId={} sessionId={} messageId={}",
                        userId, session.getId(), prior);
                SupportSendResult dup = new SupportSendResult();
                dup.setMessageId(prior);
                dup.setSessionId(session.getId());
                dup.setStatus(session.getStatus());
                dup.setDuplicate(true);
                return dup;
            }
        }

        if (!rateLimiter.allow(userId)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS.getCode(), SUPPORT_RATE_LIMITED_MESSAGE);
        }

        // 触达 + 并发安全阀：坐席若刚把会话 closed，此处 0 行 → 不落孤儿消息（H18 并发路径）
        if (sessionMapper.touchActive(session.getId()) == 0) {
            log.info("[ai][c4] 发消息时会话已离开 PH（并发关闭），拒绝落库. userId={} sessionId={}",
                    userId, session.getId());
            throw new BusinessException(ErrorCode.CONFLICT.getCode(), SESSION_CLOSED_MESSAGE);
        }

        AiSupportMessage message = new AiSupportMessage();
        message.setSessionId(session.getId());
        message.setSender(AiSupportSender.USER_VALUE);
        message.setContent(text);
        message.setReadByAgent(Boolean.FALSE);
        messageMapper.insert(message);

        // 缓冲态降级（H2）：pending_human 且此刻仍无人在线 → 本条落库同时降到 message_left。
        // pending_human+在线 → 保持缓冲（H6）；human_active/message_left → 保持（H6b/H6c）。
        String finalStatus = session.getStatus();
        boolean online = anySeatOnline();
        boolean downgraded = false;
        if (AiSupportSessionStatus.PENDING_HUMAN_VALUE.equals(finalStatus) && !online) {
            // 以 CAS 影响行数为准，而不是"想当然地报 message_left"：0 行意味着这一刻状态已变
            // （例如坐席恰好同时接入），此时如实保持读到的原状态，不向下游谎报降级。
            if (sessionMapper.casPendingToMessageLeft(session.getId()) > 0) {
                finalStatus = AiSupportSessionStatus.MESSAGE_LEFT_VALUE;
                downgraded = true;
            }
        }

        if (isNotBlank(clientMsgId)) {
            dedupStore.mark(userId, session.getId(), clientMsgId, message.getId());
        }
        log.info("[ai][c4] 人工态消息落库. userId={} sessionId={} messageId={} status={}",
                userId, session.getId(), message.getId(), finalStatus);

        // 工作台增量（§4.8）：先消息后状态 —— 买家留的这条正是会话降到 message_left 的原因
        final Long sessionId = session.getId();
        final Long messageId = message.getId();
        final String messageContent = message.getContent();
        final LocalDateTime messageCreatedAt = message.getCreatedAt();
        AfterCommit.run(() -> publisher.toAdmins(SupportEvents.MESSAGE_NEW,
                SupportEvents.messageNew(sessionId, messageId, AiSupportSender.USER_VALUE,
                        messageContent, messageCreatedAt)));
        if (downgraded) {
            AfterCommit.run(() -> publisher.toAdmins(SupportEvents.SESSION_STATUS,
                    SupportEvents.sessionStatus(sessionId, AiSupportSessionStatus.MESSAGE_LEFT_VALUE, null, null)));
        }

        SupportSendResult result = new SupportSendResult();
        result.setMessageId(message.getId());
        result.setSessionId(session.getId());
        result.setStatus(finalStatus);
        result.setDuplicate(false);
        return result;
    }

    // ------------------------------------------------------------ 会话恢复（买家侧）

    @Override
    public AiSupportSessionVO snapshot(Long userId) {
        AiSupportSession session = sessionMapper.selectLatestByUser(userId);
        AiSupportSessionVO vo = new AiSupportSessionVO();
        if (session == null) {
            // H23：无人工会话行 = 纯 AI 态；前端据此不残留旧 UI
            vo.setExists(false);
            vo.setMessages(List.of());
            return vo;
        }
        vo.setExists(true);
        vo.setSessionId(session.getId());
        vo.setStatus(session.getStatus());
        AiSupportSessionStatus st = AiSupportSessionStatus.fromValueOrNull(session.getStatus());
        vo.setStatusName(st == null ? session.getStatus() : st.getDisplayName());
        vo.setRequestedAt(session.getRequestedAt());
        vo.setActiveAt(session.getActiveAt());
        vo.setClosedAt(session.getClosedAt());
        vo.setMessages(messageMapper.selectBySessionAsc(session.getId()).stream()
                .map(this::toMessageVo).toList());
        return vo;
    }

    // ------------------------------------------------------------ 私有

    /** 仅接受买家可自报的途径；缺省补 USER_REQUEST（§4.1 前端点按钮） */
    private String normalizePublicOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return AiSupportOrigin.USER_REQUEST_VALUE;
        }
        String o = origin.trim();
        if (!AiSupportOrigin.PUBLIC_VALUES.contains(o)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), INVALID_ORIGIN_MESSAGE);
        }
        return o;
    }

    private boolean anySeatOnline() {
        return seatPresence.anySeatOnline();
    }

    /**
     * 新会话进入待接入 → 通知工作台（§4.8 {@code session_new}）。
     *
     * <p>无坐席在线时这条事件自然无人接收（没有工作台连接），这不是异常：§4.8 收敛原则
     * ——"断线不补发、权威在 PG"，工作台打开时拉快照即可看到该会话。
     * 也正因如此，即使此刻没人在线也照发不误，不在这里加"先查在线"的分支。</p>
     */
    private void publishSessionNew(AiSupportSession session) {
        String userMasked = userMasker.mask(session.getUserId());
        AfterCommit.run(() -> publisher.toAdmins(SupportEvents.SESSION_NEW,
                SupportEvents.sessionNew(session.getId(), userMasked, session.getOrigin(),
                        session.getRequestedAt(), session.getLastMsgAt())));
    }

    /**
     * 组装发起结果（§4.1 步骤 4）。
     *
     * <p>注意 pending_human + 无人在线 → 对外报 {@code message_left + promptLeave}：这是 H2 要求买家
     * 立刻看到留言引导。此时 <b>DB 行仍是 pending_human</b>，直到首条留言落库才 CAS 降级
     * —— 这样"有人接入"与"没人接入"在库里可区分（坐席上线后仍能看到这个待接入会话）。
     * 该取舍记于 DECISION-20260908-C4。</p>
     */
    private SupportRequestResult toRequestResult(AiSupportSession session, boolean online) {
        String status = session.getStatus();
        SupportRequestResult result = new SupportRequestResult();
        result.setSessionId(session.getId());

        if (AiSupportSessionStatus.PENDING_HUMAN_VALUE.equals(status) && !online) {
            result.setStatus(AiSupportSessionStatus.MESSAGE_LEFT_VALUE);
            result.setTip(TIP_OFFLINE);
            result.setPromptLeave(true);
            return result;
        }
        result.setStatus(status);
        if (AiSupportSessionStatus.MESSAGE_LEFT_VALUE.equals(status)) {
            result.setTip(TIP_EXISTING_MESSAGE_LEFT);
            result.setPromptLeave(false); // 已是留言态：复用原会话追加，不再"引导首次留言"
        } else if (AiSupportSessionStatus.HUMAN_ACTIVE_VALUE.equals(status)) {
            result.setTip(TIP_EXISTING_HUMAN_ACTIVE);
            result.setPromptLeave(false);
        } else {
            result.setTip(TIP_PENDING);
            result.setPromptLeave(false);
        }
        return result;
    }

    private AiSupportMessageVO toMessageVo(AiSupportMessage m) {
        AiSupportMessageVO vo = new AiSupportMessageVO();
        vo.setMessageId(m.getId());
        vo.setSender(m.getSender());
        vo.setContent(m.getContent());
        vo.setCreatedAt(m.getCreatedAt());
        return vo;
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
