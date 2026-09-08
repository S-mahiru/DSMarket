package com.dsmarket.modules.ai.service;

import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.ai.config.AiProperties;
import com.dsmarket.modules.ai.context.ChatScenario;
import com.dsmarket.modules.ai.dto.ChatRequest;
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
import com.dsmarket.modules.ai.sse.ChatStream;
import com.dsmarket.modules.ai.sse.DeltaChunker;
import com.dsmarket.modules.ai.tool.ChatTool;
import com.dsmarket.modules.ai.tool.ToolRegistry;
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
    private static final String TIMEOUT_MESSAGE = "回复不完整，请稍后重试。";
    private static final String TOO_MANY_MESSAGE = "这个问题处理步骤较多，暂时没处理完，请换个问法或转人工客服。";
    private static final String UPSTREAM_MESSAGE = "服务暂时不可用，请稍后重试。";
    private static final String GENERIC_TOOL_LABEL = "正在处理，请稍候…";
    private static final Map<String, String> TOOL_LABELS = Map.of(
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

    /** SseEmitter 服务端超时（controller 建 emitter 用） */
    public long sseTimeoutMillis() {
        return properties.getChat().getSseTimeout().toMillis();
    }

    // ------------------------------------------------------------ 预检（开流前）

    /**
     * 预检并占单飞行。抛出的 {@link BusinessException} 一律映射为 HTTP 状态码（429/409），
     * 不产生 SSE。返回 {@code replay=true} 表示命中幂等回放（调用方直接回 done，不进入 run）。
     */
    public Preflight open(Long userId, ChatRequest request) {
        if (request == null || request.normalizedContent().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "内容不能为空");
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
        if (p == null || p.replay()) {
            out.complete(); // 回放分支由 controller 直接回 done；此处兜底不再生成
            return;
        }
        try {
            doRound(p, out);
        } catch (Aborted aborted) {
            log.info("[ai] 客户端已断开，停止生成. userId={}", p.userId());
        } catch (ToolRoundLimit ex) {
            log.warn("[ai] 工具轮次超限，error 终止. userId={}", p.userId());
            if (!out.isCancelled()) {
                out.error(ERR_TOO_MANY_TOOL_ROUNDS, TOO_MANY_MESSAGE);
            }
        } catch (Exception e) {
            log.error("[ai] /chat 流式生成异常", e);
            if (!out.isCancelled()) {
                out.error(ERR_UPSTREAM, UPSTREAM_MESSAGE);
            }
        } finally {
            singleFlight.release(p.userId());
            out.complete();
        }
    }

    /** 预检结果（也充当单飞行"票据"） */
    public record Preflight(Long userId, ChatRequest request, boolean replay, String replayReplyId) {
    }

    /**
     * 开流后但生成未能启动（如线程池拒收）的兜底：释放单飞行，以 error 收尾，
     * 避免"流已开 200 却永远不结束 / 锁不释放"。
     */
    public void failOpen(Preflight p, ChatStream out) {
        if (p == null || p.replay()) {
            out.complete();
            return;
        }
        singleFlight.release(p.userId());
        if (!out.isCancelled()) {
            out.error(ERR_UPSTREAM, UPSTREAM_MESSAGE);
        }
        out.complete();
    }

    // ------------------------------------------------------------ 内部

    private void doRound(Preflight p, ChatStream out) {
        Long userId = p.userId();
        ChatRequest req = p.request();
        String content = req.normalizedContent();

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
            finalText = generateContent(userId, content, messages, out);
        } catch (KnowledgeLowConfidence low) {
            // C2 §3.4 知识低置信短路：不经 LLM 自由发挥 → fallback(FAQ 原文)→suggest(LOW_CONF)→done。
            // FAQ 检索优先用工具已抽取的干净 query（低置信语义上"最贴近被问问题"的问句）
            fallbackRound(userId, content, replyId, out, true, low.query());
            return;
        } catch (BusinessException be) {
            if (isTimeout(be)) {
                log.warn("[ai] 上游超时，error TIMEOUT. userId={} err={}", userId, be.getMessage());
                if (!out.isCancelled()) {
                    out.error(ERR_TIMEOUT, TIMEOUT_MESSAGE);
                }
                return;
            }
            // E4：上游连接/HTTP 层故障 → FAQ 兜底（不经 LLM），fallback→done
            log.warn("[ai] 上游故障，fallback 兜底. userId={} code={} err={}",
                    userId, be.getCode(), be.getMessage());
            fallbackRound(userId, content, replyId, out, false, null);
            return;
        }

        // 空正文兜底（正常路径：模型说空话 → 礼貌重述话术）
        String text = (finalText == null || finalText.isBlank())
                ? AiChatService.EMPTY_REPLY_TEXT : finalText;

        for (String d : DeltaChunker.chunk(text)) {
            out.delta(d);
            if (out.isCancelled()) {
                return; // E10：中断，不写回本半截轮
            }
        }
        if (out.isCancelled()) {
            return;
        }
        out.done(replyId);
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
     */
    private void fallbackRound(Long userId, String content, String replyId, ChatStream out, boolean lowConf, String faqSource) {
        if (out.isCancelled()) {
            return;
        }
        // FAQ 检索源：低置信短路时优先工具抽取的干净 query（无则回退整句用户内容）
        String source = (faqSource != null && !faqSource.isBlank()) ? faqSource : content;
        String text = faqFallback.resolve(source);
        out.fallback(text);
        if (out.isCancelled()) {
            return;
        }
        if (lowConf) {
            out.suggest("LOW_CONF");
            if (out.isCancelled()) {
                return;
            }
        }
        out.done(replyId);
        sessionStore.appendRound(userId, ChatRound.of(replyId, content, text));
    }

    /**
     * Agent 工具循环（REQ C1-F3，≤ ai.chat.max-tool-rounds）：模型要工具就逐个执行并回填
     * （每次执行前 {@code tool_begin}）；模型给出正文则返回；到上限仍要工具 → {@link ToolRoundLimit}。
     */
    private String generateContent(Long userId, String question, List<ChatMessage> messages, ChatStream out) {
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
                        .content(executeTool(userId, question, call))
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
    private String executeTool(Long userId, String question, ToolCall call) {
        ChatTool tool = toolRegistry.get(call.getName());
        if (tool instanceof SearchKnowledgeTool skt) {
            JsonNode args = toolRunner.parseArguments(call.getArguments());
            SearchKnowledgeTool.KnowledgeToolResult r = skt.run(args);
            if (r.type() == SearchKnowledgeTool.KnowledgeOutcomeType.NOT_COVERED) {
                // C3 采集钩子（仅日志，不落表；飞轮采集属 C3 切片）
                log.info("[ai][c3-hook] LOW_CONF userId={} question={}", userId, question);
                throw new KnowledgeLowConfidence(trimToNull(args.path("query").asText(null)));
            }
            // COVERED=片段渲染文本 / NO_ARG=澄清引导话术，都正常回填继续问模型
            return r.content();
        }
        return toolRunner.feed(userId, call);
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
