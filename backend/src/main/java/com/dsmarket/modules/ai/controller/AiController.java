package com.dsmarket.modules.ai.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.common.util.SecurityUtils;
import com.dsmarket.modules.ai.config.AiProperties;
import com.dsmarket.modules.ai.dto.ChatRequest;
import com.dsmarket.modules.ai.dto.ChatTurnResult;
import com.dsmarket.modules.ai.dto.DevChatRequest;
import com.dsmarket.modules.ai.dto.DevSearchRequest;
import com.dsmarket.modules.ai.provider.ChatModel;
import com.dsmarket.modules.ai.search.KnowledgeSearchResult;
import com.dsmarket.modules.ai.search.RouteSearchResult;
import com.dsmarket.modules.ai.service.AiChatStreamService;
import com.dsmarket.modules.ai.service.AiSessionService;
import com.dsmarket.modules.ai.service.KnowledgeSearchService;
import com.dsmarket.modules.ai.sse.SseChatStream;
import com.dsmarket.modules.ai.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * AI 智能客服入口。提供：
 * <ul>
 *   <li>{@code GET /meta} —— 装配自检（M0）；</li>
 *   <li>{@code POST /chat} —— SSE 正式通道（REQ C1，事件协议见 modules/ai/sse/ChatStream）；</li>
 *   <li>{@code POST /dev/chat} —— M1b 会话化调试端点（保留回归，非最终契约）；</li>
 *   <li>{@code POST /dev/knowledge-search} —— C2 双路检索调试端点（切片 2 冒烟，非最终契约）；</li>
 *   <li>{@code POST /dev/knowledge-routes} —— C2 §7 效果评估数据源：两路原始排名（含 Dense 距离，dev 用）。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiProperties properties;
    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final AiSessionService sessionService;
    private final AiChatStreamService streamService;
    private final KnowledgeSearchService knowledgeSearchService;
    private final ObjectMapper objectMapper;
    @Qualifier("aiChatExecutor")
    private final Executor aiChatExecutor;

    /** M0 冒烟：回当前装配态（key 只回掩码，不回明文） */
    @GetMapping("/meta")
    public ApiResponse<Map<String, Object>> meta() {
        String key = properties.getLlm().getApiKey();
        AiProperties.Embedding emb = properties.getEmbedding();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("module", "ai");
        data.put("status", "C1 SSE /chat");
        data.put("llm.baseUrl", properties.getLlm().getBaseUrl());
        data.put("llm.model", chatModel.modelName());
        data.put("llm.apiKeyConfigured", key != null && !key.isBlank());
        data.put("llm.apiKeyMasked", mask(key));
        data.put("embedding.vendor", emb.getVendor());
        data.put("embedding.model", emb.getModel());
        data.put("embedding.dimension", emb.getDimension());
        data.put("embedding.baseUrl", emb.getBaseUrl());
        data.put("embedding.apiKeyConfigured", emb.getApiKey() != null && !emb.getApiKey().isBlank());
        data.put("embedding.apiKeyMasked", mask(emb.getApiKey()));
        data.put("session.ttl", properties.getSession().getTtl().toMinutes() + "min");
        data.put("session.maxRounds", properties.getSession().getMaxRounds());
        data.put("chat.maxToolRounds", properties.getChat().getMaxToolRounds());
        data.put("chat.rateLimit", properties.getChat().getRateLimit() + "/min");
        data.put("chat.dedupTtl", properties.getChat().getDedupTtl().toSeconds() + "s");
        data.put("chat.sseTimeout", properties.getChat().getSseTimeout().toMinutes() + "min");
        data.put("tools", toolRegistry.specs().stream().map(s -> s.getName()).toList());
        return ApiResponse.success(data);
    }

    /**
     * SSE 正式通道（REQ C1，AI 态）。请求契约与事件协议见 REQ-20260907 §2/§3。
     *
     * <p>传输划界：鉴权 401 / 校验 400 / 限流 429 / 单飞行 409 全部开流前以 HTTP 状态码返回
     * （不发 SSE）；进流后只发 {@code tool_begin/delta/fallback/done/error}。userId 服务端注入。</p>
     */
    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequest request) {
        Long userId = SecurityUtils.requireUserId();
        // 预检（429/409 在此抛出 → HTTP 状态码，尚无 SSE）
        AiChatStreamService.Preflight pf = streamService.open(userId, request);

        SseEmitter emitter = new SseEmitter(streamService.sseTimeoutMillis());
        SseChatStream sink = new SseChatStream(emitter, objectMapper);
        if (pf.replay()) {
            // §2 clientMsgId 幂等命中：仅回 200 done（30s 内重复 → 忽略处理）
            sink.done(pf.replayReplyId());
            emitter.complete();
            return emitter;
        }
        try {
            aiChatExecutor.execute(() -> streamService.run(pf, sink));
        } catch (RejectedExecutionException e) {
            log.warn("[ai] 生成线程池已满，error 收尾. userId={}", userId);
            streamService.failOpen(pf, sink);
        }
        return emitter;
    }

    /**
     * M1b 调试：会话化多轮工具对话（每用户 Redis 单键记忆 + 单飞行 409）。
     * 最终契约将改为 SSE 通道。userId 由服务端安全上下文注入。
     */
    @PostMapping("/dev/chat")
    public ApiResponse<ChatTurnResult> devChat(@Valid @RequestBody DevChatRequest request) {
        ChatTurnResult result = sessionService.chat(SecurityUtils.requireUserId(), request.getMessage());
        return ApiResponse.success(result);
    }

    /**
     * C2 切片 2 调试端点：双路检索（BM25+Dense → RRF → 置信闸 → F6 组织）。
     * body 缺省或 query 空 → covered=false（服务层语义 = 空 top-k）。非最终契约：
     * 正式入口是 C1 的 search_knowledge 工具（切片 3 接入）。
     */
    @PostMapping("/dev/knowledge-search")
    public ApiResponse<KnowledgeSearchResult> devKnowledgeSearch(@RequestBody(required = false) DevSearchRequest request) {
        String query = request == null ? null : request.getQuery();
        return ApiResponse.success(knowledgeSearchService.search(query));
    }

    /**
     * C2 §7 效果评估数据源：两路原始 top-k（BM25/Dense 各自有序，Dense 含余弦距离），
     * 不融合不过闸不组织 —— 评估装置据此离线重算四档（①BM25 ②Dense ③RRF 等权 ④RRF 加权）
     * 与幅度闸阈值标定。body 复用 {@link DevSearchRequest}（query 字段）。生产路径不调用。
     */
    @PostMapping("/dev/knowledge-routes")
    public ApiResponse<RouteSearchResult> devKnowledgeRoutes(@RequestBody(required = false) DevSearchRequest request) {
        String query = request == null ? null : request.getQuery();
        return ApiResponse.success(knowledgeSearchService.routes(query));
    }

    private String mask(String key) {
        if (key == null || key.length() < 8) {
            return "***";
        }
        return key.substring(0, 3) + "…" + key.substring(key.length() - 4);
    }
}
