package com.dsmarket.modules.ai.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 一次模型非流式返回。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    /** 纯文本回答（未走工具时）；走工具时可能为 null */
    private String content;

    /** 若模型决定调用工具则非空（本轮先执行工具再续跑，见 Agent 编排） */
    private List<ToolCall> toolCalls;

    /** stop / tool_calls / length 等，透传模型 finish_reason */
    private String finishReason;
}
