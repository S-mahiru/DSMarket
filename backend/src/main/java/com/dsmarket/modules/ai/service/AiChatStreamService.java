package com.dsmarket.modules.ai.service;

import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.ai.config.AiProperties;
import com.dsmarket.modules.ai.context.ChatScenario;
import com.dsmarket.modules.ai.dto.ChatRequest;
import com.dsmarket.modules.ai.eval.AiEvalRecorder;
import com.dsmarket.modules.ai.eval.RecordingChatStream;
import com.dsmarket.modules.ai.eval.RoundTrace;
import com.dsmarket.modules.ai.limit.ChatRateLimiter;
import com.dsmarket.modules.ai.model.ChatMessage;
import com.dsmarket.modules.ai.model.ChatResponse;
import com.dsmarket.modules.ai.model.ChatRole;
import com.dsmarket.modules.ai.model.ChatRound;
import com.dsmarket.modules.ai.model.ToolCall;
import com.dsmarket.modules.ai.provider.ChatModel;
import com.dsmarket.modules.ai.session.AiSessionStore;
import com.dsmarket.modules.ai.session.AiSingleFlight;
import com.dsmarket.modules.ai.session.ChatDedupStore;
import com.dsmarket.modules.ai.session.RoundWindow;
import com.dsmarket.modules.ai.session.UnresolvedSignalCounter;
import com.dsmarket.modules.ai.sse.ChatStream;
import com.dsmarket.modules.ai.sse.DeltaChunker;
import com.dsmarket.modules.ai.support.ChatHumanRouter;
import com.dsmarket.modules.ai.tool.ChatTool;
import com.dsmarket.modules.ai.tool.ToolRegistry;
import com.dsmarket.modules.ai.tool.ToolResult;
import com.dsmarket.modules.ai.tool.ToolRunner;
import com.dsmarket.modules.ai.tool.impl.SearchKnowledgeTool;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * SSE /chat 正式通道编排（REQ C1）。开流前判定用 HTTP 状态码（E2/E3/E12），进流后只发事件（E4~E7）。
 *
 * <p>两段式接口（controller 负责 SseEmitter 生命周期，本服务保持传输无关、可单测）：
 * <ul>
 *   <li>{@link #open}：预检，全部开流前 → HTTP 状态码。顺序：限流 429 → clientMsgId 幂等
 *       命中回放（200 done，不占单飞行）→ 单飞行 409 CONCURRENT_GENERATION。成功后占住单飞行；</li>
 *   <li>{@link #run}：异步生成。场景行+system → 读 Redis 轮条目 → 签发 replyId → 展开消息 →
 *       工具循环（每次执行前 {@code tool_begin}）→ 正文按 {@link DeltaChunker} 切 {@code delta} →
 *       {@code done} → 写回轮条目 → 幂等登记。异常映射：工具轮次超限→error TOO_MANY_TOOL_ROUNDS、
 *       超时→error TIMEOUT、上游故障→fallback→done、其余→error UPSTREAM_ERROR；
 *       finally 释放单飞行 + 关闭流。</li>
 * </ul>
 * 客户端断开（{@link ChatStream#isCancelled()}）→ 停止生成，不写回本半截轮（REQ E10）。</p>
 *
 * <p><b>C4 切片 3 接入</b>：{@link #open} 在限流/幂等<b>之前</b>先做入口分流（PH 会话 / 锚点命中 →
 * 人工通道，不调 LLM，见 {@link ChatHumanRouter}）；生成过程在"下发前"与"写回前"各设一个竞态检查点，
 * 复查买家是否已转人工（{@link #humanTookOver}）。分流轮不进入 {@link #run}，由 controller 以
 * fallback(提示)+done 收尾。</p>
 *
 * <p><b>C5 切片 1 接入（留痕）</b>：{@link #run} 与 {@link #failOpen} 各包一层
 * {@link RecordingChatStream}，并在 finally 里经 {@link AiEvalRecorder#finishRound} 落<b>恰好一条</b>
 * AI 轮日志。留痕是纯观察：不改变事件顺序/内容/次数，写盘失败只记日志（C5 §1）。
 * replyKind 在各自的终结点显式标注，未标注者一律归 {@code interrupted}（见 {@link RoundTrace}）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatStreamService {

    /** E2 提示（开流前 HTTP 429） */
    public static final String RATE_LIMIT_MESSAGE = "发送太频繁，请稍后再试。";
    /** E7 强制终止错误码 */
    public static final String ERR_TOO_MANY_TOOL_ROUNDS = "TOO_MANY_TOOL_ROUNDS";
    /** E5/E6 超时错误码 */
    public static final String ERR_TIMEOUT = "TIMEOUT";
    /** 流中其余故障错误码 */
    public static final String ERR_UPSTREAM = "UPSTREAM_ERROR";
    /** C4 §4.6 F6 触发① 的 suggest 载荷（知识低置信兜底轮） */
    public static final String SUGGEST_LOW_CONF = "LOW_CONF";
    /** C4 §4.6 F6 触发③ 的 suggest 载荷（本会话累计未解决信号达阈值） */
    public static final String SUGGEST_UNRESOLVED = "UNRESOLVED";
    // 以下 AI 态文案为 public：主 REQ §4.2 规则 10 / §…15「AI 不谎报(自动化断言)」要求把
    // **全部**由服务端书写的 AI 态文案枚举出来逐条扫"已转接真人"类谎报（见 AiNoTransferClaimTest）。
    // 私有则断言只能靠反射或抄一份副本 —— 后者会漂移，前者掩盖可见性意图。
    public static final String TIMEOUT_MESSAGE = "回复不完整，请稍后重试。";
    public static final String TOO_MANY_MESSAGE = "这个问题处理步骤较多，暂时没处理完，请换个问法或转人工客服。";
    public static final String UPSTREAM_MESSAGE = "服务暂时不可用，请稍后重试。";
    public static final String GENERIC_TOOL_LABEL = "正在处理，请稍候…";
    public static final Map<String, String> TOOL_LABELS = Map.of(
            "query_my_order", "正在查询您的订单…",
            "search_knowledge", "正在检索知识库…");

    private final ChatModel chatModel;
    private final ToolRunner toolRunner;
    private final ToolRegistry toolRegistry;
    private final AiSessionStore sessionStore;
    private final AiSingleFlight singleFlight;
    private final ChatRateLimiter rateLimiter;
    private final ChatDedupStore dedupStore;
    private final ChatScenario scenario;
    private final AiProperties properties;
    /** FAQ 兜底解析（知识低置信短路 / 上游故障 → faq=true BM25 顶命原文直发，见主 REQ §2.3） */
    private final KnowledgeFaqFallback faqFallback;
    /** C3 问题池采集（低置信自动采集入口；采集失败静默，不影响本轮收尾） */
    private final AiIssueService issueService;
    /** C4 切片 3 入口分流（PH 会话 / 锚点命中 → 人工通道，不进 AI）与竞态复查 */
    private final ChatHumanRouter humanRouter;
    /** C4 §4.6 F6 触发③：本会话未解决信号计数（低置信轮 / 点踩），达阈值下发 suggest(UNRESOLVED) */
    private final UnresolvedSignalCounter unresolvedCounter;
    /** C5 留痕（REQ-20260908-C5 §2 AI 轮日志）：纯观察，写失败不影响本轮 */
    private final AiEvalRecorder eval;

    /** SseEmitter 服务端超时（controller 建 emitter 用） */
    public long sseTimeoutMillis() {
        return properties.getChat().getSseTimeout().toMillis();
    }

    // ------------------------------------------------------------ 预检（开流前）

    /**
     * 预检并占单飞行。抛出的 {@link BusinessException} 一律映射为 HTTP 状态码（400/429/409），
     * 不产生 SSE。返回 {@code replay=true} 表示命中幂等回放（调用方直接回 done，不进入 run）。
     */
    public Preflight open(Long userId, ChatRequest request) {
        if (request == null || request.normalizedContent().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "内容不能为空");
        }
        // C4 切片 3 入口分流（主 REQ §2.1 硬规则，顺序不可换）：
        //   ① 该 user 有 PH 会话 → 消息写入人工缓冲，不进 AI（H6 后半句）
        //   ② content 命中转人工锚点词 → 内部转人工 + 正文转存（H4/H27/P3）
        // 两条都排在**限流与幂等之前**：这两个是"消息接入"层面的路由，不是 AI 态兜底。
        // 若排在限流之后，人工态消息会重复消耗 /chat 的额度；排在幂等之后，会被 AI 通道的
        // 幂等回放当成 AI 轮次，买家拿到的 done 来自另一个通道（见 ChatHumanRouter 注释）。
        ChatHumanRouter.Routed human = humanRouter.route(
                userId, request.normalizedContent(), trimToNull(request.getClientMsgId()));
        if (human != null) {
            return new Preflight(userId, request, false, null, human);
        }
        // C1 §2 长度校验（AI 态 500）——<b>必须排在分流判定之后</b>：C1 §2 明文"锚点检测先于长度
        // 校验"，否则长申诉会在到达锚点判定前就被 400 挡掉，"长申诉可达转人工口子"这条就不成立。
        // 走到这里 = 既非 PH 会话、也没命中转人工锚点（含 §4.6 情绪词集）→ 按 AI 态上限判。
        // 锚点那一档在上面的 route() 里已消化（超长正文截断后转存）；
        // 绝对上限 4000 由 ChatRequest 的 @Size 在更外层守住（锚点命中也不豁免，防超长 DoS）。
        int contentMax = properties.getChat().getContentMax();
        if (request.normalizedContent().length() > contentMax) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(),
                    "内容过长（最多 " + contentMax + " 字）。");
        }
        // E2：限流（先于幂等/单飞行）
        if (!rateLimiter.allow(userId)) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS.getCode(), RATE_LIMIT_MESSAGE);
        }
        // §2：clientMsgId 幂等——已完成轮次 30s 内重发 → 直接回放 done（不占单飞行）
        String cid = trimToNull(request.getClientMsgId());
        if (cid != null) {
            String replayId = dedupStore.findReplyId(userId, cid);
            if (replayId != null) {
                log.info("[ai] clientMsgId 幂等命中，回放 done. userId={} replyId={}", userId, replayId);
                return new Preflight(userId, request, true, replayId);
            }
        }
        // E12：单飞行——同 userId 已有在途生成 → HTTP 409 CONCURRENT_GENERATION
        if (!singleFlight.tryAcquire(userId)) {
            throw new BusinessException(ErrorCode.CONFLICT.getCode(), AiSessionService.CONCURRENT_MESSAGE);
        }
        return new Preflight(userId, request, false, null);
    }

    // ------------------------------------------------------------ 生成（进流后，只发事件）

    /**
     * 执行一轮流式生成。调用方负责在 {@code open} 后把本方法交给线程池异步执行
     * （本方法内 finally 保证释放单飞行并 complete，正常路径不抛异常）。
     */
    public void run(Preflight p, ChatStream out) {
        if (p == null || p.replay() || p.routedToHuman()) {
            // 回放分支与人工分流分支都由 controller 直接收尾；此处兜底不再生成（更不该调 LLM）。
            // C5：这两条都不是 AI 轮（回放没生成、分流轮不调 LLM），故不产生 AI 轮留痕 ——
            // 分流轮的去向由 C4 人工通道的事件留痕负责（C5 §5 埋点表把两者分列两行）。
            out.complete();
            return;
        }
        // C5 §2：本轮留痕累加器 + 录制装饰器。装饰器是纯观察者（只转发 + 记账），
        // 关掉 ai.eval.enabled 时也只是 finishRound 不落盘 —— 事件协议一个字节都不变（§1）。
        RoundTrace trace = eval.startRound(p.userId(), p.request().normalizedContent());
        RecordingChatStream rec = new RecordingChatStream(out, trace);
        try {
            doRound(p, rec, trace);
        } catch (Aborted aborted) {
            trace.interrupt(RoundTrace.INTERRUPT_CLIENT_ABORT);
            log.info("[ai] 客户端已断开，停止生成. userId={}", p.userId());
        } catch (ToolRoundLimit ex) {
            log.warn("[ai] 工具轮次超限，error 终止. userId={}", p.userId());
            errorOrAbort(rec, trace, ERR_TOO_MANY_TOOL_ROUNDS, TOO_MANY_MESSAGE);
        } catch (Exception e) {
            log.error("[ai] /chat 流式生成异常", e);
            errorOrAbort(rec, trace, ERR_UPSTREAM, UPSTREAM_MESSAGE);
        } finally {
            singleFlight.release(p.userId());
            rec.complete();
            // C5 §2：本轮 AI 日志的**唯一**出口。放在 finally 而不是各 return 前 ——
            // 一轮的出口有十个（见 RoundTrace 类注释），逐个写迟早漏一个或写重一个，
            // 而"漏一条"在指标上是静默偏差。finishRound 自带单次守卫，重复调用被丢弃。
            eval.finishRound(trace);
        }
    }

    /**
     * 人工分流轮的 {@code done} 终止符（C4 切片 3）。
     *
     * <p>它<b>不是</b>一个真实的 AI 轮次 id：该轮不写回 Redis 会话，所以 {@code /feedback}
     * 拿它回查会落空 —— 这正是 C3 既有的"回查不到 → 静默忽略"语义，不是新增分支。
     * 之所以仍然发 {@code done}：C1 §2.3 约定流的正常收尾是 done/error 二选一，
     * 少了它客户端只能靠"连接关闭"猜，比给一个查不到的 id 更糟。</p>
     */
    public static final String REPLY_ID_HUMAN_ROUTED = "human-routed";

    /**
     * 预检结果（也充当单飞行"票据"）。
     *
     * @param human 非 null = 本轮已被 C4 分流到人工通道，调用方<b>不得</b>进入 {@link #run}，
     *              应直接以 fallback(提示)+done 收尾（见 {@link #REPLY_ID_HUMAN_ROUTED}）
     */
    public record Preflight(Long userId, ChatRequest request, boolean replay, String replayReplyId,
                            ChatHumanRouter.Routed human) {

        /** AI 态预检（human=null 的常见路径） */
        public Preflight(Long userId, ChatRequest request, boolean replay, String replayReplyId) {
            this(userId, request, replay, replayReplyId, null);
        }

        /** 本轮是否被 C4 分流到人工通道 */
        public boolean routedToHuman() {
            return human != null;
        }
    }

    /**
     * 开流后但生成未能启动（如线程池拒收）的兜底：释放单飞行，以 error 收尾，
     * 避免"流已开 200 却永远不结束 / 锁不释放"。
     */
    public void failOpen(Preflight p, ChatStream out) {
        if (p == null || p.replay() || p.routedToHuman()) {
            // routedToHuman 分支按构造不可达（controller 在提交线程池**之前**就分流返回了），
            // 加它只是让"分流轮不产生 AI 轮留痕"这条规则在这里也成立，而不是靠调用方自觉。
            out.complete();
            return;
        }
        // C5 §2：这条路径同样是一轮（线程池拒收 → error 收尾），也必须留痕 ——
        // 否则"上游/资源故障导致的失败轮"会整体缺席，P2 的失败率被系统性低估。
        RoundTrace trace = eval.startRound(p.userId(), p.request().normalizedContent());
        RecordingChatStream rec = new RecordingChatStream(out, trace);
        singleFlight.release(p.userId());
        errorOrAbort(rec, trace, ERR_UPSTREAM, UPSTREAM_MESSAGE);
        rec.complete();
        eval.finishRound(trace);
    }

    /**
     * C5 留痕配套：error 收尾与"客户端已断开"的二分。
     *
     * <p>顺序不能反 —— 先问 {@code isCancelled()} 再决定发不发 error，与改造前逐字一致。
     * 区别只在于现在<b>同时把结论记进留痕</b>：买家自己断开的轮次若被记成 {@code error}，
     * 会被算进"上游故障率"，把工程指标污染成一个假象。</p>
     */
    private void errorOrAbort(ChatStream out, RoundTrace trace, String code, String message) {
        if (out.isCancelled()) {
            trace.interrupt(RoundTrace.INTERRUPT_CLIENT_ABORT);
            return;
        }
        out.error(code, message);
        trace.replyKind(RoundTrace.ReplyKind.ERROR);
    }

    // ------------------------------------------------------------ 内部

    private void doRound(Preflight p, ChatStream out, RoundTrace trace) {
        Long userId = p.userId();
        ChatRequest req = p.request();
        String content = req.normalizedContent();

        // C4 §4.6 F6 触发③：读"本轮开始前"的累计未解决信号数。刻意在**进入生成之前**读一次，
        // 而不是在下发前再读：语义是"带着 N 次未解决进入本轮"，且避免在 delta 之后多一次 Redis 往返。
        // 本轮自身是否构成新信号（低置信）在 fallbackRound 里另行自增，两者不混。
        int unresolvedBefore = unresolvedCounter.count(userId);

        List<ChatRound> history = sessionStore.loadRounds(userId);
        String replyId = RoundWindow.nextReplyId(history);

        // C1-F1 ③：system = 固定边界提示 + 场景行（页面上下文，注入前已做归属/上架校验）
        String scenarioLine = scenario.resolve(userId, req.getContext());
        String systemContent = AiChatService.SYSTEM_PROMPT
                + (scenarioLine.isBlank() ? "" : "\n\n" + scenarioLine);
        List<ChatMessage> messages = RoundWindow.expand(
                history, systemContent, content, properties.getSession().getMaxRounds());

        String finalText;
        try {
            finalText = generateContent(userId, content, messages, out, trace);
        } catch (KnowledgeLowConfidence low) {
            // C2 §3.4 知识低置信短路：不经 LLM 自由发挥 → fallback(FAQ 原文)→suggest(LOW_CONF)→done。
            // FAQ 检索优先用工具已抽取的干净 query（低置信语义上"最贴近被问问题"的问句）
            fallbackRound(userId, content, replyId, out, true, low.query(), unresolvedBefore, trace);
            return;
        } catch (BusinessException be) {
            if (isTimeout(be)) {
                log.warn("[ai] 上游超时，error TIMEOUT. userId={} err={}", userId, be.getMessage());
                errorOrAbort(out, trace, ERR_TIMEOUT, TIMEOUT_MESSAGE);
                return;
            }
            // E4：上游连接/HTTP 层故障 → FAQ 兜底（不经 LLM），fallback→done
            log.warn("[ai] 上游故障，fallback 兜底. userId={} code={} err={}",
                    userId, be.getCode(), be.getMessage());
            fallbackRound(userId, content, replyId, out, false, null, unresolvedBefore, trace);
            return;
        }

        // C4 切片 3 竞态检查点①：模型调用是阻塞的，这几秒里买家可能已从别的入口转了人工。
        // 此刻**一个字节都还没发**，所以这是唯一能"干净作废"的位置（P16：取消在途流）。
        if (humanTookOver(userId, trace)) {
            return;
        }

        // 空正文兜底（正常路径：模型说空话 → 礼貌重述话术）
        String text = (finalText == null || finalText.isBlank())
                ? AiChatService.EMPTY_REPLY_TEXT : finalText;

        for (String d : DeltaChunker.chunk(text)) {
            out.delta(d);
            if (out.isCancelled()) {
                trace.interrupt(RoundTrace.INTERRUPT_CLIENT_ABORT);
                return; // E10：中断，不写回本半截轮
            }
        }
        if (out.isCancelled()) {
            trace.interrupt(RoundTrace.INTERRUPT_CLIENT_ABORT);
            return;
        }
        // C4 切片 3 竞态检查点②（C1 §4.1「写回前复查会话状态」，主 REQ §4.2 规则 11）：
        // 流式期间才转人工 → 不回写本半截回答。
        // 诚实边界：delta 已经推给买家了，SSE 发出去收不回，能做的只有"不写回/不采集/不下发 suggest"；
        // 上游是非流式阻塞调用，真中断做不到。此处连 done 也不发 —— 该轮没有正常结束可言。
        if (humanTookOver(userId, trace)) {
            return;
        }
        // C4 §4.6 F6 触发③：正常答复轮也附加建议标记。
        // 为什么必须挂在这里、而不只挂 fallback 轮：触发③ 的**唯一真增量是点踩**——低置信轮已经
        // 由触发① 发过 suggest(LOW_CONF)（见 fallbackRound），而点踩发生时这一轮的流早已关闭，
        // 没有第二次机会在同一轮里提示。若只在 fallback 轮判定，买家"点踩两次 → 再问一句正常问题"
        // 这条最典型的路径永远看不到气泡。
        maybeSuggestUnresolved(out, unresolvedBefore);
        out.done(replyId);
        trace.replyKind(RoundTrace.ReplyKind.AI);
        sessionStore.appendRound(userId, ChatRound.of(replyId, content, text));

        String cid = trimToNull(req.getClientMsgId());
        if (cid != null) {
            dedupStore.mark(userId, cid, replyId);
        }
    }

    /**
     * fallback 收尾（不经 LLM，主 REQ §2.3 FAQ 兜底）：fallback(FAQ 顶命原文/固定话术)→
     * [suggest]→done，并把该文本作为本轮内容写回。lowConf=true 时在 fallback 后、done 前发一条
     * {@code suggest("LOW_CONF")}（C4-F6 触发①，仅知识低置信短路走；上游故障等其它 fallback 不发）。
     *
     * <p><b>C4-F6 触发③ 在这条路上的两个分支</b>（2026-09-10，见 DECISION D20/D21）：
     * 低置信轮<b>自增</b>未解决计数（它本身就是一次未解决），但<b>不追加</b>第二条 suggest ——
     * C1 §3 约定 {@code fallback} 后至多一条，触发① 已经占了这个位置；非低置信的兜底轮
     * （上游故障）<b>不算</b>未解决信号，但若买家此前已攒够计数，照样附加气泡。</p>
     *
     * @param unresolvedBefore 进入本轮时的累计未解决信号数（doRound 读一次后透传，避免重复读 Redis）
     */
    private void fallbackRound(Long userId, String content, String replyId, ChatStream out, boolean lowConf,
                               String faqSource, int unresolvedBefore, RoundTrace trace) {
        if (out.isCancelled()) {
            trace.interrupt(RoundTrace.INTERRUPT_CLIENT_ABORT);
            return;
        }
        // C4 切片 3 竞态检查点①：与 doRound 同理，此处尚未下发任何事件（P16 取消在途流）
        if (humanTookOver(userId, trace)) {
            return;
        }
        // FAQ 检索源：低置信短路时优先工具抽取的干净 query（无则回退整句用户内容）
        String source = (faqSource != null && !faqSource.isBlank()) ? faqSource : content;
        String text = faqFallback.resolve(source);
        out.fallback(text);
        if (out.isCancelled()) {
            trace.interrupt(RoundTrace.INTERRUPT_CLIENT_ABORT);
            return;
        }
        if (lowConf) {
            // C4 切片 3 竞态检查点②：faqFallback.resolve 是一次检索调用（有真实耗时），
            // 期间可能已转人工 —— 此时既不下发 suggest，也不采集（主 REQ §4.2 规则 11 明确列出这两项）。
            if (humanTookOver(userId, trace)) {
                return;
            }
            out.suggest(SUGGEST_LOW_CONF);
            if (out.isCancelled()) {
                trace.interrupt(RoundTrace.INTERRUPT_CLIENT_ABORT);
                return;
            }
            // C3-F1 低置信采集：本轮已 commit 兜底+建议、未被中断 → 采入问题池（干净问句优先，无则整句）
            issueService.collectLowConfidence(userId, source);
            // C4-F6 触发③：本轮的"未解决"记一笔。打在采集之后、与采集同处"本轮已 commit"的语义块里；
            // 此处**不发** suggest(UNRESOLVED) —— 上面那条 LOW_CONF 已占用本轮唯一的名额。
            int n = unresolvedCounter.increment(userId);
            log.info("[ai][c4] 未解决信号 +1（低置信轮）. userId={} count={}", userId, n);
        } else {
            // 非低置信兜底（上游故障）：不计未解决信号（这不是"答不好"，是"上游挂了"），
            // 但买家此前攒够的计数依然有效，照样给出口。
            maybeSuggestUnresolved(out, unresolvedBefore);
        }
        out.done(replyId);
        // C5 §2 replyKind 的 faq/fallback 二分：两者都发 fallback 事件、都经 faqFallback 取文，
        // 差别只在**为什么**走到这条路 —— 低置信（知识库答不了）vs 上游故障（模型挂了）。
        // 这两档在 P2 里的含义完全相反（前者是知识覆盖问题、后者是工程可用性问题），
        // 混成一档就没法从日志区分"该补知识"还是"该修链路"。
        trace.replyKind(lowConf ? RoundTrace.ReplyKind.FAQ : RoundTrace.ReplyKind.FALLBACK);
        sessionStore.appendRound(userId, ChatRound.of(replyId, content, text));
    }

    /**
     * C4 §4.6 F6 触发③（2026-09-10 裁决，DECISION D20/D21）：累计未解决信号达阈值 → 下发
     * {@code suggest("UNRESOLVED")} 气泡。<b>只发气泡，不改会话状态</b> —— 会话仍 {@code ai_active}，
     * 买家点了气泡才走 {@code POST /support/request} 转人工（origin 在那一刻记 {@code AI_SUGGEST}）。
     * 这正是 §4.6 硬规则 / H5 的原话，也是本触发**不需要**新增 {@code origin} 取值的原因。
     *
     * <p>调用方负责保证：已经过竞态检查点（未转人工）、且本轮尚未发过 suggest。</p>
     */
    private void maybeSuggestUnresolved(ChatStream out, int unresolvedBefore) {
        int threshold = properties.getSupport().getUnresolvedThreshold();
        if (threshold <= 0 || unresolvedBefore < threshold) {
            return;
        }
        out.suggest(SUGGEST_UNRESOLVED);
    }

    /**
     * Agent 工具循环（REQ C1-F3，≤ ai.chat.max-tool-rounds）：模型要工具就逐个执行并回填
     * （每次执行前 {@code tool_begin}）；模型给出正文则返回；到上限仍要工具 → {@link ToolRoundLimit}。
     */
    private String generateContent(Long userId, String question, List<ChatMessage> messages, ChatStream out,
                                   RoundTrace trace) {
        int maxRounds = properties.getChat().getMaxToolRounds();
        for (int round = 0; round < maxRounds; round++) {
            if (out.isCancelled()) {
                throw new Aborted();
            }
            ChatResponse resp = chatModel.chat(messages, toolRegistry.specs());
            List<ToolCall> calls = resp.getToolCalls();
            if (calls == null || calls.isEmpty()) {
                return resp.getContent();
            }
            // 模型本轮要调工具：带 tool_calls 的 assistant 消息原样回传
            messages.add(ChatMessage.builder()
                    .role(ChatRole.ASSISTANT)
                    .content(resp.getContent())
                    .toolCalls(calls)
                    .build());
            for (ToolCall call : calls) {
                if (call.getName() == null) {
                    continue;
                }
                if (!out.isCancelled()) {
                    out.toolBegin(call.getName(), labelFor(call.getName()));
                }
                messages.add(ChatMessage.builder()
                        .role(ChatRole.TOOL)
                        .toolCallId(call.getId())
                        .content(executeTool(userId, question, call, trace))
                        .build());
            }
        }
        throw new ToolRoundLimit();
    }

    /**
     * 执行单个工具并返回回填正文。search_knowledge 走 typed {@code run()} 短路判定，
     * 其余工具走通用折叠（ToolRunner）。低置信（NOT_COVERED）不喂回模型 → 抛
     * {@link KnowledgeLowConfidence} 由 doRound 收口到 FAQ 兜底。
     */
    private String executeTool(Long userId, String question, ToolCall call, RoundTrace trace) {
        ChatTool tool = toolRegistry.get(call.getName());
        if (tool instanceof SearchKnowledgeTool skt) {
            JsonNode args = toolRunner.parseArguments(call.getArguments());
            SearchKnowledgeTool.KnowledgeToolResult r = skt.run(args);
            // C5 §2 retrieval：在工具出口当场上账 —— 出了这个作用域，topScore/两路席位就没了，
            // 低置信轮尤其如此（结果对象被丢弃，只剩一个"没覆盖"的结论，而那正是要看的一档）
            if (r.retrieval() != null) {
                trace.retrieval(r.retrieval().kind(), r.retrieval().topScore(),
                        r.retrieval().topCategory(), r.retrieval().conf());
            }
            if (r.type() == SearchKnowledgeTool.KnowledgeOutcomeType.NOT_COVERED) {
                trace.tool("search_knowledge", false, "LOW_CONF");
                // C3 低置信采集在 fallbackRound 低置信分支统一处理（此处只抛内部异常走 FAQ 兜底短路）
                throw new KnowledgeLowConfidence(trimToNull(args.path("query").asText(null)));
            }
            // COVERED=片段渲染文本 / NO_ARG=澄清引导话术，都正常回填继续问模型
            boolean ok = r.type() == SearchKnowledgeTool.KnowledgeOutcomeType.COVERED;
            trace.tool("search_knowledge", ok, ok ? null : "NO_ARG");
            return r.content();
        }
        // C5 §2 tools[].ok：走未折叠的 run() 取真实结果（feed 会把原因抹成同一句 FOLD_TEXT，
        // 拿它记账等于把每一档失败都记成同一个"失败"，P2 就没法按失败原因分层了）。
        // 回填给模型的文本仍走同一套折叠语义，两条路不会漂移。
        ToolResult result = toolRunner.run(userId, call);
        trace.tool(call.getName(), result.isOk(), result.isOk() ? null : result.getError());
        return result.isOk() ? result.getContent() : ToolRunner.FOLD_TEXT;
    }

    /** 客户端断开（E10）→ 内部终止，不补任何事件 */
    private static final class Aborted extends RuntimeException {
    }

    /**
     * 知识低置信短路（search_knowledge NOT_COVERED）→ 由 doRound 收口 FAQ 兜底，不经模型自由发挥。
     * {@code query} 为本次工具实际检索的干净问句（可能为 null），供 FAQ 兜底优先复用。
     */
    private static final class KnowledgeLowConfidence extends RuntimeException {
        private final String query;

        KnowledgeLowConfidence(String query) {
            this.query = query;
        }

        String query() {
            return query;
        }
    }

    /** 工具轮次超限（E7）→ error TOO_MANY_TOOL_ROUNDS */
    private static final class ToolRoundLimit extends RuntimeException {
    }

    /**
     * C4 切片 3 竞态复查（主 REQ §4.2 规则 11「AI 流与转人工互斥」/ C1 §4.1「写回前复查会话状态」）。
     *
     * <p>语义：AI 生成在途时买家转了人工 → 转人工胜出，<b>被打断轮不回写、不采集、不下发 suggest</b>（P16）。
     * 上游 LLM 是阻塞非流式调用，<b>无法真中断</b>，本方法只能在生成返回后的检查点丢弃结果 ——
     * 代价是这次模型调用已经花掉，换来的是买家不会在人工态里收到一条 AI 答复。</p>
     *
     * <p>代价是一次按 {@code user_id} 索引的 DB 查询（{@code status IN PH} 走
     * {@code idx_ai_support_session_user}）。每轮最多查两次（下发前 + 写回前），
     * 用一次索引查询换"人工态绝不混入 AI 答复"这条硬规则，值。</p>
     *
     * @return true = 已转人工，调用方必须立即 return，不得继续下发或写回
     */
    private boolean humanTookOver(Long userId, RoundTrace trace) {
        if (!humanRouter.hasHumanTakeover(userId)) {
            return false;
        }
        log.info("[ai][c4] 在途打断：生成期间买家已转人工，本轮作废（不回写/不采集/不下发 suggest）. userId={}", userId);
        // C5：留下打断原因。replyKind 与客户端断开同为 interrupted，但口径相反 ——
        // 这一条是"人工态绝不混入 AI 答复"这条硬规则生效的证据，不是买家放弃。
        trace.interrupt(RoundTrace.INTERRUPT_HUMAN_TAKEOVER);
        return true;
    }

    private boolean isTimeout(BusinessException be) {
        String msg = be.getMessage();
        return be.getCode() == 500 && msg != null && (msg.contains("超时") || msg.contains("中断"));
    }

    private String labelFor(String tool) {
        return TOOL_LABELS.getOrDefault(tool, GENERIC_TOOL_LABEL);
    }

    private String trimToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
