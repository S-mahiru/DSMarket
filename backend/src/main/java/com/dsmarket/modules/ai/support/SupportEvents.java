package com.dsmarket.modules.ai.support;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人工态 SSE 事件的载荷工厂（C4 §4.7 买家通道 / §4.8 admin 通道，权威表）。
 *
 * <p>把"事件类型 + 载荷字段"集中在这里，是为了让两份权威表在代码里<b>只有一处落点</b>：
 * 载荷字段名写错、少了 eventId、把 activeAt 塞进 session_new —— 都只可能在这里发生，
 * 也只在这里被测试压住。各调用方只管"发生了什么事"，不管"事件长什么样"。</p>
 *
 * <p>可空字段一律<b>只在非空时写入</b>（null 不占键），前端据"键是否存在"判断，
 * 与 §4.8 表里 {@code activeAt?}/{@code closedAt?} 的问号语义一致。</p>
 */
public final class SupportEvents {

    // ------------------------------------------------------------ 事件类型常量（权威表 §4.7/§4.8，勿增删）

    /** 买家通道：状态推进（pending_human / human_active） */
    public static final String HUMAN_STATUS = "human_status";
    /** 买家通道：坐席或系统消息实时到达 */
    public static final String HUMAN_MESSAGE = "human_message";
    /** 买家通道：无坐席在线时告知 */
    public static final String HUMAN_OFFLINE_TIP = "human_offline_tip";
    /** 买家通道：会话结束 */
    public static final String HUMAN_CLOSE = "human_close";

    /** admin 通道：新会话进入待接入 */
    public static final String SESSION_NEW = "session_new";
    /** admin 通道：会话状态迁移 */
    public static final String SESSION_STATUS = "session_status";
    /** admin 通道：新消息到达 */
    public static final String MESSAGE_NEW = "message_new";
    /** admin 通道：本坐席在线态变化 */
    public static final String SEAT_STATUS = "seat_status";

    // ------------------------------------------------------------ 状态/原因常量

    /** human_status.state 取值（§4.7；注意 closed 不在此列 —— 结束走 human_close） */
    public static final String STATE_PENDING_HUMAN = "pending_human";
    public static final String STATE_HUMAN_ACTIVE = "human_active";

    /** seat_status.reason 取值（§4.8：仅 login/logout 两因；心跳超时由前端自判，无 heartbeat_timeout） */
    public static final String REASON_LOGIN = "login";
    public static final String REASON_LOGOUT = "logout";

    private SupportEvents() {
    }

    // ------------------------------------------------------------ 买家通道载荷（§4.7）

    /** {@code human_status {state, text}} */
    public static Map<String, Object> humanStatus(String state, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("text", text);
        return m;
    }

    /** {@code human_message {messageId, sender, content, createdAt}} */
    public static Map<String, Object> humanMessage(Long messageId, String sender, String content,
                                                   LocalDateTime createdAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("messageId", messageId);
        m.put("sender", sender);
        m.put("content", content);
        m.put("createdAt", createdAt);
        return m;
    }

    /** {@code human_offline_tip {text}} */
    public static Map<String, Object> humanOfflineTip(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text);
        return m;
    }

    /** {@code human_close {text}} */
    public static Map<String, Object> humanClose(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text);
        return m;
    }

    // ------------------------------------------------------------ admin 通道载荷（§4.8）

    /** {@code session_new {sessionId, userMasked, origin, requestedAt, lastMsgAt}} */
    public static Map<String, Object> sessionNew(Long sessionId, String userMasked, String origin,
                                                 LocalDateTime requestedAt, LocalDateTime lastMsgAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("userMasked", userMasked);
        m.put("origin", origin);
        m.put("requestedAt", requestedAt);
        m.put("lastMsgAt", lastMsgAt);
        return m;
    }

    /** {@code session_status {sessionId, status, activeAt?, closedAt?}} */
    public static Map<String, Object> sessionStatus(Long sessionId, String status,
                                                    LocalDateTime activeAt, LocalDateTime closedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("status", status);
        if (activeAt != null) {
            m.put("activeAt", activeAt);
        }
        if (closedAt != null) {
            m.put("closedAt", closedAt);
        }
        return m;
    }

    /**
     * {@code message_new {sessionId, eventId, sender, content, createdAt}}。
     *
     * <p>{@code eventId} 取消息主键：§4.8 要求前端以 {@code (sessionId, eventId)} 去重
     * （本地乐观渲染 + 服务端事件校正）。</p>
     */
    public static Map<String, Object> messageNew(Long sessionId, Long eventId, String sender,
                                                 String content, LocalDateTime createdAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("eventId", eventId);
        m.put("sender", sender);
        m.put("content", content);
        m.put("createdAt", createdAt);
        return m;
    }

    /** {@code seat_status {seat, state, reason}} */
    public static Map<String, Object> seatStatus(Long adminId, String state, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seat", adminId);
        m.put("state", state);
        m.put("reason", reason);
        return m;
    }
}
