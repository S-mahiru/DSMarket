package com.dsmarket.modules.ai.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.common.util.SecurityUtils;
import com.dsmarket.modules.ai.dto.AiSupportSessionVO;
import com.dsmarket.modules.ai.dto.SupportMessageRequest;
import com.dsmarket.modules.ai.dto.SupportRequestResult;
import com.dsmarket.modules.ai.dto.SupportSendResult;
import com.dsmarket.modules.ai.enums.AiSupportOrigin;
import com.dsmarket.modules.ai.service.AiSupportSessionService;
import com.dsmarket.modules.ai.session.UnresolvedSignalCounter;
import com.dsmarket.modules.ai.support.SupportEvalTracer;
import com.dsmarket.modules.ai.support.SupportStreamRegistry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 人工客服买家侧入口（C4 切片 1）。路径在 {@code /api/v1/ai/**} 的 authenticated 规则下，
 * userId 一律由 {@link SecurityUtils#requireUserId()} 从 JWT 取 —— 请求体不带 user_id，
 * 买家无法操作他人会话（§7 权限边界）。
 *
 * <p>四个动作对应 §4.4 的买家侧协议：发起（request）→ 发消息（message）→ 回读（session）
 * → 实时通道（stream，切片 2 加入）。回读与 stream 的分工是协议的核心：
 * <b>回读是历史与终态的唯一来源，stream 只推连接期间的新事件</b>。</p>
 *
 * <p><b>错误信封</b>：统一走 {@code BusinessException(intCode, message)} → HTTP 状态码 +
 * {@code ApiResponse{code:int}}，与 C1 单飞行 409、C3 一致；不引入第二套字符串 code 信封
 * （裁决记于 DECISION-20260908-C4）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai/support")
@RequiredArgsConstructor
public class AiSupportController {

    private final AiSupportSessionService supportService;
    private final SupportStreamRegistry streams;
    /** C4 §4.6 F6 触发③：转人工即清零未解决计数（本类与 {@code ChatHumanRouterImpl} 是全库仅有的两个转人工入口） */
    private final UnresolvedSignalCounter unresolvedCounter;
    /** C5 切片 2：support_request / message 事件留痕（§5 第 4 行） */
    private final SupportEvalTracer evalTracer;

    /**
     * 发起转人工（C4-F1）。幂等：已有会话 → 返回现状，不新建（H7）。
     *
     * <p>这也是 F6 建议气泡的落地口：买家点气泡 → 本接口（{@code origin=AI_SUGGEST}）→ 建人工会话。
     * 建成后 AI 侧的"未解决计数"清零（C4 §4.6 触发③）——<b>已经交给人了</b>。
     * 注意清零点在 {@code supportService.request} <b>之后</b>：它可能抛 400/409，抛了就不该清。</p>
     *
     * @param origin 触发途径，缺省 USER_REQUEST；仅接受 USER_REQUEST / AI_SUGGEST，其他 400
     */
    @PostMapping("/request")
    public ApiResponse<SupportRequestResult> request(
            @RequestParam(required = false) String origin) {
        Long userId = SecurityUtils.requireUserId();
        SupportRequestResult result = supportService.request(userId, origin);
        unresolvedCounter.clear(userId);
        log.info("[ai][c4] 转人工请求. userId={} status={} sessionId={}",
                userId, result.getStatus(), result.getSessionId());
        // C5 §5 第 4 行：放在 request 成功之后 —— 抛了 400/409 就没有"转人工请求"这个事实，
        // 记了会让转人工成功率的分母凭空多一条。
        // origin 记的是**买家本次请求的途径**（缺省补 USER_REQUEST），而不是复用会话上原有的 origin：
        // 两者在幂等复用时会不同，而"买家这次想怎么转"才是分析入口分流效果要看的量。
        evalTracer.supportRequest(userId,
                origin == null ? AiSupportOrigin.USER_REQUEST_VALUE : origin,
                result.getSessionId(), result.getStatus());
        return ApiResponse.success(result);
    }

    /**
     * 人工态发消息（C4-F4，PH 三态通用）。错误：401 未登录 / 400 内容非法 /
     * 409 NO_ACTIVE_SESSION|SESSION_CLOSED（H18）/ 429 限流。
     */
    @PostMapping("/message")
    public ApiResponse<SupportSendResult> message(@Valid @RequestBody SupportMessageRequest request) {
        Long userId = SecurityUtils.requireUserId();
        SupportSendResult result = supportService.sendMessage(
                userId, request.normalizedContent(), request.getClientMsgId());
        if (!result.isDuplicate()) {
            // 幂等命中（duplicate=true）**不记**：这一条没有落库，记了会让"留言数"随重试虚增
            // —— 而留言数正是"留言响应率"的分母。
            evalTracer.userMessage(userId, result.getSessionId(), result.getMessageId(), result.getStatus());
        }
        return ApiResponse.success(result);
    }

    /**
     * 买家侧会话快照（§4.4 会话恢复）——<b>人工会话历史的唯一来源</b>，前端每次打开抽屉先拉它，
     * 再开 SSE 收增量（SSE 不重放历史）。无人工会话行 → {@code exists=false}（H23）。
     */
    @GetMapping("/session")
    public ApiResponse<AiSupportSessionVO> session() {
        return ApiResponse.success(supportService.snapshot(SecurityUtils.requireUserId()));
    }

    /**
     * 买家人工态 SSE 通道（§4.7）：常驻连接，收 {@code human_status / human_message /
     * human_offline_tip / human_close}。
     *
     * <p><b>只推连接期间的新事件，不重放历史</b>：前端必须先 {@code GET /session} 恢复历史与状态，
     * 再开本通道收增量（§4.4 会话恢复权威）。断线期间发生的事（坐席回复、结束）靠重开后
     * 重新 GET 兜底 —— 这也是 H21「断线期间被 closed 仍能回溯」的实现方式。</p>
     *
     * <p>本通道与 C1 的 {@code /chat}（AI 态）以及 admin 通道三者物理隔离：
     * 事件类型白名单在 {@code SseSupportStreams} 里强制，发错类型会抛异常而非静默混发。</p>
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        Long userId = SecurityUtils.requireUserId();
        log.info("[ai][c4] 买家人工态 SSE 连接建立. userId={}", userId);
        return streams.registerBuyer(userId);
    }
}
