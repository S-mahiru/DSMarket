package com.dsmarket.modules.ai.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一轮会话的持久化条目（Redis `ai:session:{userId}` 数组元素，REQ C1 §4.1 权威结构）。
 *
 * <p>只存「用户原问句 + AI 最终话术」，不含中间 tool 往返（工具内部细节不进入会话记忆，
 * 与 C1「轮条目 {replyId, userContent, assistantContent, ts}」一致）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRound {

    /** 本轮回执编号（该用户内唯一，随 done/replyId 下发，供反馈回查） */
    private String replyId;

    /** 用户原问句 */
    private String userContent;

    /** AI 最终话术（完整回答全文） */
    private String assistantContent;

    /** 写入时间（epoch millis），供留痕/排查 */
    private long ts;

    public static ChatRound of(String replyId, String userContent, String assistantContent) {
        return ChatRound.builder()
                .replyId(replyId)
                .userContent(userContent)
                .assistantContent(assistantContent)
                .ts(System.currentTimeMillis())
                .build();
    }
}
