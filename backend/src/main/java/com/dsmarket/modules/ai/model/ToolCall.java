package com.dsmarket.modules.ai.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型发起的一次工具调用（function calling）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall {

    /** 调用 id（模型侧分配，TOOL 结果须回填） */
    private String id;
    /** 工具名（与 ChatTool.name() 对应） */
    private String name;
    /** 参数 JSON 原文（字符串），执行时解析为 JsonNode */
    private String arguments;
}
