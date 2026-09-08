package com.dsmarket.modules.ai.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单条对话消息。
 *
 * <ul>
 *   <li>ASSISTANT 发起工具调用时 {@link #toolCalls} 非空（content 可空，随 model）；</li>
 *   <li>TOOL 结果回填用 {@link #toolCallId} 串回对应调用 id。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    private ChatRole role;
    private String content;

    /** 仅 role=ASSISTANT：本轮发起的工具调用列表（发回给模型时原样带出） */
    private List<ToolCall> toolCalls;

    /** 仅 role=TOOL：所响应的工具调用 id */
    private String toolCallId;
}
