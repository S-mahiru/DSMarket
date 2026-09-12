package com.dsmarket.modules.ai.support;

import com.dsmarket.modules.ai.config.AiProperties;
import com.dsmarket.modules.ai.dto.SupportRequestResult;
import com.dsmarket.modules.ai.dto.SupportSendResult;
import com.dsmarket.modules.ai.enums.AiSupportOrigin;
import com.dsmarket.modules.ai.enums.AiSupportSessionStatus;
import com.dsmarket.modules.ai.service.AiSupportSessionService;
import com.dsmarket.modules.ai.session.UnresolvedSignalCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ChatHumanRouter} 实现：{@code /chat} 入口的人工分流。
 *
 * <p><b>权威依据</b>：主 REQ §2.1「入口分流（硬）」——① 该 user 存在 PH 会话 → 本接口不进入 AI 流程，
 * content 按 C4-F4 规则写入人工消息缓冲（含 pending_human 窗口）；② content 命中转人工锚点词集
 * → 转 C4-F1。两条都<b>不调 LLM</b>。对应判定 H6 后半句（"误走 /chat 亦缓冲不进入 AI"）、
 * H4（锚点词触发）、H27/P3（锚点正文转存）。</p>
 *
 * <p>锚点词集是<b>两套的并集</b>（{@link AnchorWordMatcher}）：§5.1 权威 HUMAN_REQUEST 词集，
 * 加上 C4 §4.6 F6 的情绪/投诉词集。后者原设计只发 suggest 气泡，因与 §5.1 重叠（"投诉"两边都在、
 * 行为相反），2026-09-10 裁决为<b>一律直接转人工</b>。</p>
 *
 * <p><b>为什么排在限流/幂等之前</b>：分流是"消息接入"层面的路由，不是 AI 态的兜底。顺序错了有两个
 * 具体后果——① 先过 {@code /chat} 的 20 次/分限流，人工态消息会同时吃掉 AI 额度，而人工通道另有
 * 独立的 10 次/分（§4.4，口径是"防骚扰坐席"）；② 先过 {@code /chat} 的 clientMsgId 幂等回放，人工态
 * 消息会被当成 AI 轮次回放 done，买家拿到的答复来自另一个通道。</p>
 *
 * <p><b>为什么单独一个组件、而不是塞进 {@code AiChatStreamService}</b>：锚点路径要"先转人工、再转存
 * 正文"两步（§4.1 统一动作步骤 3 与步骤 6），必须各走一次 {@link AiSupportSessionService} 的<b>代理</b>
 * 调用，才能让 {@code sendMessage} 上的 {@code @Transactional} 真正生效——同类内 {@code this.} 自调用会
 * 绕过代理，事务静默失效。本类持有代理引用，故把这两步编排在这里并自带事务边界。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHumanRouterImpl implements ChatHumanRouter {

    // ------------------------------------------------------------ 回给 /chat 通道的提示文案
    // 由 /chat 的 fallback 事件下发（见 AiController）。措辞一律"如实说清消息去哪了"，
    // 不得出现"AI 已为您转接真人"式谎报（主 REQ §4.2 规则 10）。

    /** PH=pending_human：消息进了人工缓冲，还没人接入 */
    public static final String NOTICE_HUMAN_PENDING = "您的消息已发送给人工客服，正在为您接入，请稍候。";
    /** PH=human_active：坐席正在服务 */
    public static final String NOTICE_HUMAN_ACTIVE = "您的消息已发送给人工客服。";
    /** PH=message_left：留言态追加 */
    public static final String NOTICE_HUMAN_LEFT = "您的留言已提交，客服会尽快回复。";
    /** 锚点命中且有人在线：转人工 + 正文已一并转交 */
    public static final String NOTICE_ANCHOR_PENDING = "正在为您接入人工客服，您的消息已一并转交。";
    /** 锚点命中但无人在线：诚实降级为留言（不谎报有人接） */
    public static final String NOTICE_ANCHOR_LEFT = "当前暂无客服在线，您的消息已作为留言提交，我们会尽快回复。";

    private final AiSupportSessionService supportService;
    private final AnchorWordMatcher anchorMatcher;
    private final AiProperties properties;
    /** C4 §4.6 F6 触发③：转人工即清零"未解决信号"——已经交给人了，不该继续算在 AI 的账上 */
    private final UnresolvedSignalCounter unresolvedCounter;
    /** C5 切片 2：本方两条分流路径都是"买家消息进了人工通道"的事实来源（§5 第 4 行） */
    private final SupportEvalTracer evalTracer;

    @Override
    @Transactional
    public Routed route(Long userId, String content, String clientMsgId) {
        // ① PH 会话优先于锚点：买家已在人工态时，再说什么都只是人工通道的一条消息，
        //    不该因为这句话里带了"投诉"就重复触发一次转人工（§4.1 步骤 3 的幂等语义）。
        if (supportService.hasHumanTakeover(userId)) {
            SupportSendResult sent = supportService.sendMessage(userId, content, clientMsgId);
            log.info("[ai][c4] /chat 入口命中 PH 会话，已转人工通道缓冲（未调 LLM）. userId={} sessionId={} status={}",
                    userId, sent.getSessionId(), sent.getStatus());
            clearUnresolved(userId);
            recordUserMessage(userId, sent);
            return new Routed(sent.getSessionId(), sent.getStatus(), noticeForStatus(sent.getStatus()));
        }

        // ② 锚点命中 → 内部转人工（origin=ANCHOR_HIT，买家接口无法自报该途径）+ 正文转存
        String word = anchorMatcher.match(content);
        if (word == null) {
            return null;
        }
        SupportRequestResult requested = supportService.requestByAnchor(userId);
        SupportSendResult sent = supportService.sendMessage(userId, anchorContent(content), null);
        log.info("[ai][c4] /chat 入口锚点命中，已自动转人工（未调 LLM）. userId={} sessionId={} word={} status={}",
                userId, sent.getSessionId(), word, sent.getStatus());
        clearUnresolved(userId);
        recordSupportRequest(userId, requested);
        recordUserMessage(userId, sent);
        return new Routed(sent.getSessionId(), sent.getStatus(), noticeForAnchor(sent.getStatus(), requested));
    }

    @Override
    public boolean hasHumanTakeover(Long userId) {
        return supportService.hasHumanTakeover(userId);
    }

    // ------------------------------------------------------------ 私有

    /**
     * P3 决议（§4.1 步骤 6）：锚点原文截断至 ≤{@code user-content-max} 再转存，
     * <b>不转存禁止</b>——否则长申诉正文丢失，买家得重打一遍。
     *
     * <p><b>这条现在是可达的</b>（2026-09-10 按 C1 §2 原文实现长度分层后）：content 命中锚点即
     * <b>豁免 AI 态的 500 上限</b>，只受绝对上限 4000 约束，所以 content ∈ (2000, 4000] 且命中锚点
     * 时本方法真会截断。此前 {@code ChatRequest} 平铺一个 {@code @Size(max=500)}，把这条压成了
     * 永不触发的死代码——那是<b>实现漏了 C1 §2 的"锚点豁免"条款</b>，不是需求冲突。</p>
     */
    /**
     * C4-F6 触发③ 的清理：<b>凡是买家进了人工通道，AI 侧的未解决计数就归零</b>。
     *
     * <p>放在 route 的两条成功分支上（而不是塞进 {@code AiSupportSessionService}）：那个服务的构造
     * 参数被一条结构断言锁死为"只含会话存储/限流/幂等/坐席接缝/事件/脱敏/配置"（REQ §4.4
     * "人工态不经 LLM"的编译期级保证），为了一个 AI 侧计数器去放宽那条护栏不划算。
     * 代价是"转人工入口"有两处（此处 + {@code AiSupportController.request}），
     * <b>两处都必须清</b>；这两处是全库仅有的转人工入口，已在 C4 收口核对中记录。</p>
     */
    private void clearUnresolved(Long userId) {
        unresolvedCounter.clear(userId);
    }

    /**
     * C5 留痕：锚点路径的"转人工请求"事实（origin 恒 ANCHOR_HIT，买家接口无法自报该途径）。
     *
     * <p>{@code status} 取 {@code requestByAnchor} 的直接结果（不是随后落消息的写后状态）：
     * 两个事实分开记才好解释 —— "转人工那一步的结果"在这里，"这条正文落库后会话变成什么态"
     * 在 {@link #recordUserMessage} 的 {@code sessionStatus} 里。</p>
     */
    private void recordSupportRequest(Long userId, SupportRequestResult requested) {
        if (requested == null) {
            return;
        }
        // 事务提交后才落盘：本方法在 @Transactional 里被调用，回滚时这条"已转人工"的事实并不成立
        AfterCommit.run(() -> evalTracer.supportRequest(userId, AiSupportOrigin.ANCHOR_HIT_VALUE,
                requested.getSessionId(), requested.getStatus()));
    }

    /** C5 留痕：买家消息真的落库了才记（幂等命中 duplicate=true 时没落库，记了会让留言数虚增） */
    private void recordUserMessage(Long userId, SupportSendResult sent) {
        if (sent == null || sent.isDuplicate()) {
            return;
        }
        AfterCommit.run(() -> evalTracer.userMessage(userId, sent.getSessionId(),
                sent.getMessageId(), sent.getStatus()));
    }

    private String anchorContent(String content) {
        int max = properties.getSupport().getUserContentMax();
        String text = content == null ? "" : content.trim();
        if (text.length() <= max) {
            return text;
        }
        // 留一位给省略号，保证截断后总长仍 ≤ max（sendMessage 按同一上限校验，超一字即 400）
        return text.substring(0, max - 1) + "…";
    }

    private String noticeForStatus(String status) {
        if (AiSupportSessionStatus.MESSAGE_LEFT_VALUE.equals(status)) {
            return NOTICE_HUMAN_LEFT;
        }
        if (AiSupportSessionStatus.PENDING_HUMAN_VALUE.equals(status)) {
            return NOTICE_HUMAN_PENDING;
        }
        return NOTICE_HUMAN_ACTIVE;
    }

    /**
     * 锚点路径的提示以<b>转存后</b>的状态为准（{@code sent.getStatus()}）：转人工那一步可能因为
     * 无人在线对外报 message_left，但真正决定买家体验的是这条正文落库后的会话状态
     * （pending_human 可能被 CAS 降到 message_left）。
     */
    private String noticeForAnchor(String finalStatus, SupportRequestResult requested) {
        if (AiSupportSessionStatus.MESSAGE_LEFT_VALUE.equals(finalStatus)) {
            return NOTICE_ANCHOR_LEFT;
        }
        log.debug("[ai][c4] 锚点转人工完成. sessionId={} requestTip={}",
                requested == null ? null : requested.getSessionId(),
                requested == null ? null : requested.getTip());
        return NOTICE_ANCHOR_PENDING;
    }
}
