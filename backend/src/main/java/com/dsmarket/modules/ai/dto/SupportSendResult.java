package com.dsmarket.modules.ai.dto;

import lombok.Data;

/**
 * 人工态发消息结果（C4 §4.4）。
 *
 * <p>{@code status} 取<b>写入之后</b>的会话状态，可能与请求时不同：pending_human 且此刻仍无坐席在线
 * → 本条落库同时把会话降到 message_left（H2），返回值如实反映迁移后的态，前端据此切 UI。</p>
 *
 * <p>{@code duplicate=true}（§4.4 P6 幂等命中）：本次<b>未落库</b>，{@code messageId} 是先前那条的 id，
 * 便于前端直接把重试当作成功。</p>
 */
@Data
public class SupportSendResult {

    private Long messageId;

    private Long sessionId;

    /** 写入后的会话状态：pending_human / human_active / message_left */
    private String status;

    /** true = clientMsgId 幂等命中，未重复落库 */
    private boolean duplicate;
}
