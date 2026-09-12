package com.dsmarket.modules.ai.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.common.util.SecurityUtils;
import com.dsmarket.modules.ai.dto.AdminSupportBoardVO;
import com.dsmarket.modules.ai.dto.AdminSupportMessageVO;
import com.dsmarket.modules.ai.dto.AdminSupportSessionDetailVO;
import com.dsmarket.modules.ai.dto.AgentReplyRequest;
import com.dsmarket.modules.ai.service.AiSupportAdminService;
import com.dsmarket.modules.ai.support.SupportStreamRegistry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 坐席工作台接口（C4 §4.2/§4.5/§4.8）。
 *
 * <p><b>权限由 SecurityConfig 把住</b>：{@code /api/v1/admin/**} → {@code hasRole("ADMIN")}，
 * 普通买家调用一律 403（H10 验收项）。故本控制器不写角色判断 —— 鉴权只有一处，
 * 多写一处就多一处会忘记同步的地方。</p>
 *
 * <p>坐席身份取 {@code SecurityUtils.requireUserId()}（R5 = R3 ADMIN 兼任，REQ §2）。
 * 注意会话表<b>不记录接入人</b>：本模块不做坐席分配/路由（§1 明确不做），
 * 所以 take/reply/close 都不需要"是谁做的"。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/ai/support")
@RequiredArgsConstructor
public class AdminSupportController {

    private final AiSupportAdminService adminService;
    private final SupportStreamRegistry streams;

    /** 工作台三列表快照（§4.5）：SSE 重连后必须先拉它再收增量（§4.8 原则①） */
    @GetMapping("/sessions")
    public ApiResponse<AdminSupportBoardVO> sessions() {
        return ApiResponse.success(adminService.board());
    }

    /** 会话详情（§4.5）：完整消息 + AI 对话回放（L 决议）+ 脱敏用户标识 */
    @GetMapping("/session/{id}/messages")
    public ApiResponse<AdminSupportSessionDetailVO> messages(@PathVariable Long id) {
        return ApiResponse.success(adminService.detail(id));
    }

    /** 接入（§4.2）：并发双接入仅一成功，落败方 409（H8） */
    @PostMapping("/session/{id}/take")
    public ApiResponse<Void> take(@PathVariable Long id) {
        adminService.take(id);
        return ApiResponse.success();
    }

    /** 标记已读（§4.5 决议 J）：清本会话红点；<b>不回推买家</b>，故无 SSE 事件 */
    @PostMapping("/session/{id}/read")
    public ApiResponse<Map<String, Object>> read(@PathVariable Long id) {
        int cleared = adminService.read(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cleared", cleared);
        return ApiResponse.success(data);
    }

    /** 坐席回复（§4.4/§4.5）：≤4000 字；回复<b>不自动关闭</b>会话（P2 决议，H25） */
    @PostMapping("/session/{id}/message")
    public ApiResponse<AdminSupportMessageVO> reply(@PathVariable Long id,
                                                    @Valid @RequestBody AgentReplyRequest request) {
        AdminSupportMessageVO vo = adminService.reply(id, request.normalizedContent());
        log.info("[ai][c4] 坐席回复已发送. sessionId={} messageId={}", id, vo.getMessageId());
        return ApiResponse.success(vo);
    }

    /** 结束会话（§4.5）：PH 任一态 → closed，向买家推 human_close（H26） */
    @PostMapping("/session/{id}/close")
    public ApiResponse<Void> close(@PathVariable Long id) {
        adminService.close(id);
        return ApiResponse.success();
    }

    /**
     * 心跳（§4.2）：每 15s 一次，刷新在线键 TTL 45s。
     * 返回 {@code newlyOnline} 供前端识别"我这次上线了"（与 SSE 的 seat_status 同一信号）。
     */
    @PostMapping("/heartbeat")
    public ApiResponse<Map<String, Object>> heartbeat() {
        Long adminId = SecurityUtils.requireUserId();
        boolean newlyOnline = adminService.heartbeat(adminId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("newlyOnline", newlyOnline);
        return ApiResponse.success(data);
    }

    /** 显式登出（§4.2 在线退出③）：清在线键 + 推 seat_status(offline, logout) */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        adminService.logout(SecurityUtils.requireUserId());
        return ApiResponse.success();
    }

    /**
     * 工作台 SSE 通道（§4.8）：常驻连接，收 {@code session_new/session_status/message_new/seat_status}。
     *
     * <p><b>SSE 断线 ≠ 掉线</b>（在线由独立心跳维持，§4.8 原则①）：本接口断开不触发任何
     * seat_status 事件，重连后前端先拉快照再收增量。不推历史、不支持 Last-Event-ID 补发。</p>
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        Long adminId = SecurityUtils.requireUserId();
        log.info("[ai][c4] 工作台 SSE 连接建立. adminId={}", adminId);
        return streams.registerAdmin(adminId);
    }
}
