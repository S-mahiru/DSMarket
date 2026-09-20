package com.dsmarket.modules.ai.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.common.util.SecurityUtils;
import com.dsmarket.modules.ai.dto.ChatRequest;
import com.dsmarket.modules.ai.dto.FeedbackRequest;
import com.dsmarket.modules.ai.service.AiChatStreamService;
import com.dsmarket.modules.ai.service.AiIssueService;
import com.dsmarket.modules.ai.session.UnresolvedSignalCounter;
import com.dsmarket.modules.ai.sse.SseChatStream;
import com.dsmarket.modules.ai.support.SupportEvalTracer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * AI 智能客服入口。提供：
 * <ul>
 *   <li>{@code POST /chat} —— SSE 正式通道（REQ C1，事件协议见 modules/ai/sse/ChatStream）；</li>
 *   <li>{@code POST /feedback} —— 买家点赞/点踩（C3-F2，点踩入问题池）。</li>
 * </ul>
 *
 * <p><b>本类只保留生产路径。</b>原先挂在这里的装配自检 {@code GET /meta}（M0）与三个
 * {@code /dev/*} 调试端点，现已<b>全部</b>迁至 {@link AiDevController}（该类带
 * {@code @Profile("dev")}，生产不装配）。三个 {@code /dev/*} 的 URL 未变；{@code /meta}
 * 顺带归入 dev 命名空间，现为 {@code GET /api/v1/ai/dev/meta} —— 迁移前全仓 grep
 * 确认**零消费方**（前端、后端、测试均无引用）。</p>
 *
 * <p>{@code /meta} 必须一并挡住的理由同那三个端点：它只需登录即可调，且回传
 * {@code llm.apiKeyMasked} / {@code embedding.apiKeyMasked}（审计 12-readiness-audit §1.5）。
 * 它是那批 M0 自检口里最后一个漏网的 —— 上一批只搬了 {@code /dev/*} 三个。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiChatStreamService streamService;
    private final AiIssueService issueService;
    /** C4 §4.6 F6 触发③：点踩是一条买家可见性为零的"未解决"信号，在此累加计数 */
    private final UnresolvedSignalCounter unresolvedCounter;
    /** C5 切片 2：feedback 事件留痕（§5 第 3 行） */
    private final SupportEvalTracer evalTracer;
    private final ObjectMapper objectMapper;
    @Qualifier("aiChatExecutor")
    private final Executor aiChatExecutor;

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
        if (pf.routedToHuman()) {
            // C4 切片 3：本轮已在入口被分流到人工通道（PH 会话缓冲 / 锚点自动转人工），
            // 消息已按 C4-F4 落 PG，此处只如实告知买家消息去哪了 —— 不调 LLM、不写回 Redis 会话。
            // 用 fallback 而非新事件类型：不新增 C1 事件协议，且语义上它就是"非模型回复"。
            sink.fallback(pf.human().notice());
            sink.done(AiChatStreamService.REPLY_ID_HUMAN_ROUTED);
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
     * 买家反馈（主 REQ §2.4 / C3-F2）。点赞（satisfied:true）仅记日志；
     * 点踩（false）凭 replyId 回查该轮原问句入问题池（C3 采集，失败/回查不到静默）。
     * R2 本人；userId 服务端注入。
     *
     * <p><b>C4 §4.6 F6 触发③ 接入点</b>：点踩是本条链路上<b>唯一不经过 {@code /chat} SSE 的</b>未解决
     * 信号——点踩发生时那一轮的流早就关了（{@code /feedback} 是普通 HTTP），所以那一轮没有任何机会
     * 给买家提示。计数在此累加，等<b>下一轮</b>正常答复时才下发 {@code suggest("UNRESOLVED")}。</p>
     */
    @PostMapping("/feedback")
    public ApiResponse<Void> feedback(@Valid @RequestBody FeedbackRequest request) {
        Long userId = SecurityUtils.requireUserId();
        boolean satisfied = Boolean.TRUE.equals(request.getSatisfied());
        boolean collected = false;
        if (satisfied) {
            // 点赞：仅记录，不采集
            log.info("[ai] feedback 点赞 userId={} replyId={} reason={}",
                    userId, request.getReplyId(), request.getReason());
        } else {
            // 只有"识别到一次新鲜点踩"才计数：重复点击/回查不到 → false，不累加（防连点虚增）
            if (issueService.collectDislike(userId, request.getReplyId())) {
                collected = true;
                int n = unresolvedCounter.increment(userId);
                log.info("[ai][c4] 未解决信号 +1（点踩）. userId={} replyId={} count={}",
                        userId, request.getReplyId(), n);
            }
        }
        // C5 §5 第 3 行：留痕放在最后统一一条 —— 两条分支各自写会让"点赞也记一次"这类改动
        // 迟早只改一边。collected 是本切片新增的可观测量（见 SupportEvalTracer#feedback）。
        evalTracer.feedback(userId, request.getReplyId(), satisfied, request.getReason(), collected);
        return ApiResponse.success();
    }
}
