package com.dsmarket.modules.ai.service.impl;

import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.ai.config.AiProperties;
import com.dsmarket.modules.ai.dto.AdminSupportBoardVO;
import com.dsmarket.modules.ai.dto.AdminSupportMessageVO;
import com.dsmarket.modules.ai.dto.AdminSupportSessionDetailVO;
import com.dsmarket.modules.ai.dto.AdminSupportSessionVO;
import com.dsmarket.modules.ai.dto.AiSessionSummaryVO;
import com.dsmarket.modules.ai.dto.SupportUnreadCount;
import com.dsmarket.modules.ai.entity.AiSupportMessage;
import com.dsmarket.modules.ai.entity.AiSupportSession;
import com.dsmarket.modules.ai.enums.AiSupportSender;
import com.dsmarket.modules.ai.enums.AiSupportSessionStatus;
import com.dsmarket.modules.ai.mapper.AiSupportMessageMapper;
import com.dsmarket.modules.ai.mapper.AiSupportSessionMapper;
import com.dsmarket.modules.ai.model.ChatRound;
import com.dsmarket.modules.ai.service.AiSupportAdminService;
import com.dsmarket.modules.ai.session.AiSessionStore;
import com.dsmarket.modules.ai.support.AfterCommit;
import com.dsmarket.modules.ai.support.SeatOnlineRegistry;
import com.dsmarket.modules.ai.support.SupportEventPublisher;
import com.dsmarket.modules.ai.support.SupportEvalTracer;
import com.dsmarket.modules.ai.support.SupportEvents;
import com.dsmarket.modules.ai.support.SupportSeatPresence;
import com.dsmarket.modules.ai.support.SupportUserMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 坐席工作台实现（C4 切片 2）。
 *
 * <p>依赖里<b>没有</b> ChatModel / EmbeddingClient —— 坐席侧不经模型是结构保证。
 * {@link AiSessionStore} 是唯一的"AI 相关"依赖，且只用于<b>原文直读</b>回放（L 决议），
 * 不触发任何生成。</p>
 *
 * <p>并发胜负一律由 SQL 的 CAS 影响行数决定（接入 / 结束），不做"先查后改"——
 * 工作台天然会双开标签页、双人同时点，竞态是常态而非边界。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSupportAdminServiceImpl implements AiSupportAdminService {

    // ------------------------------------------------------------ 对外话术常量（落库/推送共用同一份文本）

    /** 接入时落库的 SYSTEM 消息与 human_status.text（§4.2） */
    public static final String TAKE_NOTICE_TEXT = "客服已接入，正在为您服务。";
    /** §4.3 等待超时的 SYSTEM 提示（H28：买家 SSE 断开时靠它落库保语义） */
    public static final String BUSY_NOTICE_TEXT = "当前坐席繁忙，可留言或稍后再试。";
    /** §4.7 human_close 的 text */
    public static final String CLOSE_TEXT = "本次人工会话已结束。";

    // ------------------------------------------------------------ 错误话术常量

    public static final String SESSION_NOT_FOUND_MESSAGE = "人工会话不存在。";
    public static final String TAKE_CONFLICT_MESSAGE = "该会话已被其他坐席接入，或已不处于等待接入状态。";
    public static final String REPLY_CLOSED_MESSAGE = "本次人工会话已结束，无法回复。";
    public static final String REPLY_NOT_TAKEN_MESSAGE = "请先接入该会话再回复。";
    public static final String CLOSE_CONFLICT_MESSAGE = "该会话已结束。";
    public static final String EMPTY_REPLY_MESSAGE = "回复内容不能为空。";

    private final AiSupportSessionMapper sessionMapper;
    private final AiSupportMessageMapper messageMapper;
    private final AiSessionStore aiSessionStore;
    private final SupportUserMasker userMasker;
    private final SupportEventPublisher publisher;
    private final SupportSeatPresence seatPresence;
    private final SeatOnlineRegistry seatRegistry;
    private final AiProperties properties;
    /**
     * C5 切片 2 留痕（接入/结束/回复/在线）。
     *
     * <p><b>为什么允许它进这个依赖集</b>：{@code AiSupportAdminServiceImplTest} 有一条
     * "依赖集固定"的结构断言，本类新增依赖必须回那里过一遍"会不会让工作台经过模型"。
     * 结论是不会：{@link SupportEvalTracer} 的依赖只有 {@link AiEvalRecorder}（纯 JSON 组装 + 写盘）
     * 与 {@link SupportSeatPresence}（只读 Redis 键），链路里没有任何 ChatModel/EmbeddingClient/
     * KnowledgeSearch；且 {@code AiEvalRecorder} 所有方法吞异常，"留痕坏了"不改变工作台行为。
     * 断言已同步更新并额外锁死"tracer 自身也不含模型依赖"（防它日后长出模型调用）。</p>
     */
    private final SupportEvalTracer evalTracer;

    // ------------------------------------------------------------ §4.5 三列表

    @Override
    public AdminSupportBoardVO board() {
        List<AiSupportSession> sessions = sessionMapper.selectByStatuses(AiSupportSessionStatus.PH_VALUES);

        Map<Long, Integer> unread = unreadCounts(sessions.stream().map(AiSupportSession::getId).toList());
        Map<Long, String> masked = userMasker.maskAll(sessions.stream().map(AiSupportSession::getUserId).toList());

        Map<String, List<AdminSupportSessionVO>> byStatus = new LinkedHashMap<>();
        for (String s : AiSupportSessionStatus.PH_VALUES) {
            byStatus.put(s, new ArrayList<>());
        }
        // 保持 SQL 的 last_msg_at 倒序：按列表顺序归桶，桶内自然有序
        for (AiSupportSession s : sessions) {
            byStatus.get(s.getStatus()).add(toSessionVo(s, masked, unread));
        }

        AdminSupportBoardVO board = new AdminSupportBoardVO();
        board.setQueued(byStatus.get(AiSupportSessionStatus.PENDING_HUMAN_VALUE));
        board.setActive(byStatus.get(AiSupportSessionStatus.HUMAN_ACTIVE_VALUE));
        board.setLeft(byStatus.get(AiSupportSessionStatus.MESSAGE_LEFT_VALUE));
        return board;
    }

    @Override
    public AdminSupportSessionDetailVO detail(Long sessionId) {
        AiSupportSession session = requireSession(sessionId);
        AdminSupportSessionDetailVO vo = new AdminSupportSessionDetailVO();
        vo.setSessionId(session.getId());
        vo.setUserMasked(userMasker.mask(session.getUserId()));
        vo.setOrigin(session.getOrigin());
        vo.setStatus(session.getStatus());
        vo.setStatusName(displayName(session.getStatus()));
        vo.setRequestedAt(session.getRequestedAt());
        vo.setActiveAt(session.getActiveAt());
        vo.setClosedAt(session.getClosedAt());
        vo.setLastMsgAt(session.getLastMsgAt());
        vo.setMessages(messageMapper.selectBySessionAsc(sessionId).stream().map(this::toMessageVo).toList());
        vo.setAiSummary(aiSummary(session.getUserId()));
        return vo;
    }

    // ------------------------------------------------------------ §4.2 接入

    @Override
    @Transactional
    public void take(Long sessionId) {
        AiSupportSession session = requireSession(sessionId);

        if (sessionMapper.casPendingToActive(sessionId) == 0) {
            // H8：并发双接入，或者该会话已被降级/结束 —— 落败方如实拿 409，不假装接入成功
            log.info("[ai][c4] 接入失败（CAS 0 行）：会话已被他人接入或状态已变. sessionId={} status={}",
                    sessionId, session.getStatus());
            throw new BusinessException(ErrorCode.CONFLICT.getCode(), TAKE_CONFLICT_MESSAGE);
        }

        // 回读 active_at：CAS 里写的是 DB 时钟的 now()，事件载荷要从库里取回同一个值，
        // 否则工作台按事件渲染的接入时间会与随后拉到的快照差出一个应用/DB 时钟漂移。
        AiSupportSession taken = sessionMapper.selectById(sessionId);
        Long userId = session.getUserId();

        // 先登记 session_status 再登记 message_new：after-commit 按登记顺序回调，
        // 于是工作台的"三列表移动"先于"消息追加"到达，前端不会先看到消息后看到列表变动。
        AfterCommit.run(() -> publisher.toAdmins(SupportEvents.SESSION_STATUS,
                SupportEvents.sessionStatus(sessionId, AiSupportSessionStatus.HUMAN_ACTIVE_VALUE,
                        taken == null ? null : taken.getActiveAt(), null)));

        // §4.5：接入即回放缓冲消息 → 同步批量置已读（坐席看到的就是"已看过的"）
        int readCount = messageMapper.markUserMessagesRead(sessionId);

        // §4.2：接入时落一条 SYSTEM 消息（买家断线时靠回读看到"客服已接入"）
        AiSupportMessage notice = insertMessage(sessionId, AiSupportSender.SYSTEM_VALUE, TAKE_NOTICE_TEXT, true);

        AfterCommit.run(() -> {
            publisher.toBuyer(userId, SupportEvents.HUMAN_STATUS,
                    SupportEvents.humanStatus(SupportEvents.STATE_HUMAN_ACTIVE, TAKE_NOTICE_TEXT));
            publisher.toBuyer(userId, SupportEvents.HUMAN_MESSAGE,
                    SupportEvents.humanMessage(notice.getId(), AiSupportSender.SYSTEM_VALUE,
                            notice.getContent(), notice.getCreatedAt()));
        });
        log.info("[ai][c4] 坐席已接入. sessionId={} userId={} 缓冲置已读={}条",
                sessionId, userId, readCount);
        // C5 §5 第 4 行「接入」：§3 的"转人工成功率"= request→接入收敛，没有这条就无从重算
        AfterCommit.run(() -> evalTracer.sessionTransition(userId, sessionId,
                SupportEvalTracer.SESSION_TAKE, AiSupportSessionStatus.HUMAN_ACTIVE_VALUE));
    }

    @Override
    public int read(Long sessionId) {
        requireSession(sessionId);
        int cleared = messageMapper.markUserMessagesRead(sessionId);
        // 刻意不推任何事件：已读回执不回推买家（§4.5 决议 J）
        log.info("[ai][c4] 工作台标记已读. sessionId={} 清零红点={}", sessionId, cleared);
        return cleared;
    }

    // ------------------------------------------------------------ §4.4/§4.5 回复

    @Override
    @Transactional
    public AdminSupportMessageVO reply(Long sessionId, String content) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), EMPTY_REPLY_MESSAGE);
        }
        int max = properties.getSupport().getAgentContentMax();
        if (text.length() > max) {
            // 配置可调，故服务层复核一次，不只信入参注解上的 4000
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "回复过长（最多 " + max + " 字）。");
        }

        AiSupportSession session = requireSession(sessionId);
        String status = session.getStatus();
        if (AiSupportSessionStatus.CLOSED_VALUE.equals(status)) {
            throw new BusinessException(ErrorCode.CONFLICT.getCode(), REPLY_CLOSED_MESSAGE);
        }
        if (AiSupportSessionStatus.PENDING_HUMAN_VALUE.equals(status)) {
            // 未接入就回复会造出"没人接过、却已经有坐席发言"的会话，工作台三列表也对不上账
            throw new BusinessException(ErrorCode.CONFLICT.getCode(), REPLY_NOT_TAKEN_MESSAGE);
        }

        AiSupportMessage message = insertMessage(sessionId, AiSupportSender.AGENT_VALUE, text, true);
        // P2 决议：回复不迁移状态 —— message_left 保持 message_left（H25），human_active 保持 human_active
        Long userId = session.getUserId();
        AfterCommit.run(() -> publisher.toBuyer(userId, SupportEvents.HUMAN_MESSAGE,
                SupportEvents.humanMessage(message.getId(), AiSupportSender.AGENT_VALUE,
                        message.getContent(), message.getCreatedAt())));
        log.info("[ai][c4] 坐席回复. sessionId={} messageId={} status保持={}",
                sessionId, message.getId(), status);
        // C5：留言响应率的"响应"分子。记的是**写后状态**（= 原状态，P2 决议回复不迁移状态）
        AfterCommit.run(() -> evalTracer.agentMessage(userId, sessionId, message.getId(), status));
        return toMessageVo(message);
    }

    // ------------------------------------------------------------ §4.5 结束

    @Override
    @Transactional
    public void close(Long sessionId) {
        AiSupportSession session = requireSession(sessionId);
        if (sessionMapper.casClose(sessionId) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT.getCode(), CLOSE_CONFLICT_MESSAGE);
        }
        AiSupportSession closed = sessionMapper.selectById(sessionId);
        Long userId = session.getUserId();
        AfterCommit.run(() -> {
            publisher.toBuyer(userId, SupportEvents.HUMAN_CLOSE, SupportEvents.humanClose(CLOSE_TEXT));
            publisher.toAdmins(SupportEvents.SESSION_STATUS,
                    SupportEvents.sessionStatus(sessionId, AiSupportSessionStatus.CLOSED_VALUE,
                            null, closed == null ? null : closed.getClosedAt()));
        });
        log.info("[ai][c4] 坐席结束会话. sessionId={} userId={}", sessionId, userId);
        AfterCommit.run(() -> evalTracer.sessionTransition(userId, sessionId,
                SupportEvalTracer.SESSION_CLOSE, AiSupportSessionStatus.CLOSED_VALUE));
    }

    // ------------------------------------------------------------ §4.2 心跳 / 登出

    @Override
    public boolean heartbeat(Long adminId) {
        boolean newlyOnline = seatRegistry.markOnline(adminId);
        if (newlyOnline) {
            // §4.8 seat_status 的 login 因：仅"由离线转在线"这一次推，后续续期静默
            // （多标签共享同一在线键，第二个标签心跳不该再报一次 login —— §4.2 P5）
            publisher.toAdmin(adminId, SupportEvents.SEAT_STATUS,
                    SupportEvents.seatStatus(adminId, "online", SupportEvents.REASON_LOGIN));
            log.info("[ai][c4] 坐席上线. adminId={}", adminId);
            // C5：只在"由离线转在线"这一次记 —— 心跳是 15s 一次的续期，每次记会把日志淹掉，
            // 而重算坐席在线时长只需要上线/登出两个端点（与 §4.8 只推一次 login 同口径）
            evalTracer.seat(adminId, SupportEvalTracer.SEAT_LOGIN);
        }
        return newlyOnline;
    }

    @Override
    public void logout(Long adminId) {
        // 先推后清：此刻该坐席的连接还在，推送才到得了；清键只影响"还有没有人在线"的判定
        publisher.toAdmin(adminId, SupportEvents.SEAT_STATUS,
                SupportEvents.seatStatus(adminId, "offline", SupportEvents.REASON_LOGOUT));
        seatRegistry.markOffline(adminId);
        log.info("[ai][c4] 坐席显式登出. adminId={}", adminId);
        evalTracer.seat(adminId, SupportEvalTracer.SEAT_LOGOUT);
    }

    // ------------------------------------------------------------ §4.3 等待超时降级

    @Override
    @Transactional
    public int sweepWaitTimeout() {
        if (!seatPresence.anySeatOnline()) {
            // §4.3 该行前提是"转人工时坐席在线但无人接入"。无人在线时买家在 request 那一步就已经
            // 拿到了诚实提示（"暂无客服在线"），此时再补一句"坐席繁忙"等于凭空说有客服 —— 不谎报。
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now().minus(properties.getSupport().getWaitTimeout());
        List<AiSupportSession> waiting = sessionMapper.selectPendingBefore(cutoff);

        int notified = 0;
        for (AiSupportSession session : waiting) {
            // 仍是 pending_human 才会被查到 → 已接入/已留言的会话天然出局，无需额外标记位
            if (messageMapper.countSystemMessage(session.getId(), BUSY_NOTICE_TEXT) > 0) {
                continue; // 本会话已提示过，不重复刷屏（提示只发一次）
            }
            AiSupportMessage notice = insertMessage(session.getId(), AiSupportSender.SYSTEM_VALUE,
                    BUSY_NOTICE_TEXT, true);
            Long userId = session.getUserId();
            AfterCommit.run(() -> publisher.toBuyer(userId, SupportEvents.HUMAN_MESSAGE,
                    SupportEvents.humanMessage(notice.getId(), AiSupportSender.SYSTEM_VALUE,
                            notice.getContent(), notice.getCreatedAt())));
            notified++;
            log.info("[ai][c4] 30s 无人接自动提示. sessionId={} userId={}", session.getId(), userId);
        }
        return notified;
    }

    // ------------------------------------------------------------ 私有

    private AiSupportSession requireSession(Long sessionId) {
        AiSupportSession session = sessionId == null ? null : sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), SESSION_NOT_FOUND_MESSAGE);
        }
        return session;
    }

    /**
     * 落一条消息 + 触达会话 + 推 admin 流 {@code message_new}。
     *
     * <p>触达（刷新 {@code last_msg_at}）对所有发送方都做：该列语义就是"最近消息时间"，
     * 三列表按它排序，漏掉 SYSTEM 消息会让列表顺序与实际不符。</p>
     *
     * <p>推 {@code message_new} 覆盖三种发送方（§4.8：买家消息→红点+置顶；坐席/系统消息→追加，
     * 本端回显也推，供多标签页同步）。买家的 {@code human_message} 由调用方按需另推
     * —— 不是每条工作台可见的消息都该到买家（例如未来的内部备注）。</p>
     */
    private AiSupportMessage insertMessage(Long sessionId, String sender, String content, boolean readByAgent) {
        AiSupportMessage message = new AiSupportMessage();
        message.setSessionId(sessionId);
        message.setSender(sender);
        message.setContent(content);
        message.setReadByAgent(readByAgent);
        messageMapper.insert(message);
        sessionMapper.touchActive(sessionId);

        AfterCommit.run(() -> publisher.toAdmins(SupportEvents.MESSAGE_NEW,
                SupportEvents.messageNew(sessionId, message.getId(), sender, content,
                        message.getCreatedAt())));
        return message;
    }

    /** 跨会话未读计数：空集合直接短路（空 IN () 是非法 SQL，且本就要避免无谓往返） */
    private Map<Long, Integer> unreadCounts(Collection<Long> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (SupportUnreadCount c : messageMapper.countUnreadBySessions(sessionIds)) {
            counts.put(c.getSessionId(), c.getCnt() == null ? 0 : c.getCnt());
        }
        return counts;
    }

    /**
     * AI 对话回放（§4.5 L 决议）：Redis 原文直读最近 N 轮，<b>不调模型、不生成摘要</b>。
     * 空列表 = 无记录（Redis 会话已过期或转人工前没聊过）。
     *
     * <p>这里再兜一层 try/catch：{@link AiSessionStore} 的生产实现自身已降级，但工作台
     * "打不开详情"比"没有 AI 回放"严重得多，所以取不到就当没有，绝不因为回放失败让详情页 500。</p>
     */
    private List<AiSessionSummaryVO> aiSummary(Long userId) {
        List<ChatRound> rounds;
        try {
            rounds = aiSessionStore.loadRounds(userId);
        } catch (Exception e) {
            log.warn("[ai][c4] AI 对话回放读取失败，按无记录处理. userId={} err={}", userId, e.getMessage());
            return List.of();
        }
        if (rounds == null || rounds.isEmpty()) {
            return List.of();
        }
        int n = properties.getSupport().getAiSummaryRounds();
        List<ChatRound> tail = rounds.size() <= n ? rounds : rounds.subList(rounds.size() - n, rounds.size());
        return tail.stream().map(r -> {
            AiSessionSummaryVO vo = new AiSessionSummaryVO();
            vo.setUserContent(r.getUserContent());
            vo.setAssistantContent(r.getAssistantContent());
            vo.setTs(r.getTs());
            return vo;
        }).collect(Collectors.toList());
    }

    private AdminSupportSessionVO toSessionVo(AiSupportSession s, Map<Long, String> masked,
                                              Map<Long, Integer> unread) {
        AdminSupportSessionVO vo = new AdminSupportSessionVO();
        vo.setSessionId(s.getId());
        vo.setUserMasked(userMasker.maskOrFallback(masked, s.getUserId()));
        vo.setOrigin(s.getOrigin());
        vo.setStatus(s.getStatus());
        vo.setStatusName(displayName(s.getStatus()));
        vo.setRequestedAt(s.getRequestedAt());
        vo.setActiveAt(s.getActiveAt());
        vo.setClosedAt(s.getClosedAt());
        vo.setLastMsgAt(s.getLastMsgAt());
        vo.setUnreadCount(unread.getOrDefault(s.getId(), 0));
        return vo;
    }

    private AdminSupportMessageVO toMessageVo(AiSupportMessage m) {
        AdminSupportMessageVO vo = new AdminSupportMessageVO();
        vo.setMessageId(m.getId());
        vo.setSender(m.getSender());
        vo.setContent(m.getContent());
        vo.setCreatedAt(m.getCreatedAt());
        vo.setReadByAgent(m.getReadByAgent());
        return vo;
    }

    private String displayName(String status) {
        AiSupportSessionStatus st = AiSupportSessionStatus.fromValueOrNull(status);
        return st == null ? status : st.getDisplayName();
    }
}
