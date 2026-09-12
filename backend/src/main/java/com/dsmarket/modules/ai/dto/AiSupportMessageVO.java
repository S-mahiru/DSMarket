package com.dsmarket.modules.ai.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 人工消息视图（买家侧回读用，C4 §4.4 会话恢复）。
 *
 * <p>有意<b>不含</b> {@code readByAgent}：已读回执不外露给买家（§4.5 J 决议"买家无需知道坐席已读"）。
 * 这个字段的缺失是协议的一部分，不是遗漏 —— 想加它之前先回头看决议。</p>
 */
@Data
public class AiSupportMessageVO {

    private Long messageId;

    /** USER / AGENT / SYSTEM（大写，§3.2） */
    private String sender;

    private String content;

    private LocalDateTime createdAt;
}
