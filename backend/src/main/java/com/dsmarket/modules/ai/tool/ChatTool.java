package com.dsmarket.modules.ai.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 客服工具（function calling 的服务端执行单元）。
 *
 * <p>数据权限边界（REQ §4.2）：{@link #execute(Long, JsonNode)} 的 userId
 * 一律由服务端从安全上下文注入（SecurityUtils.requireUserId()），工具不得自行取信前端参数——
 * 越权防护写进 SQL/服务层，命中即折叠或拒答，差异只进审计日志。</p>
 */
public interface ChatTool {

    /** 工具名（与模型 tool_calls.function.name 对应，须稳定） */
    String name();

    /** 给模型的自然语言描述（影响模型何时选用） */
    String description();

    /** OpenAI function.parameters 的 JSON Schema；无参工具返回 null */
    JsonNode parameters();

    /**
     * 执行工具。
     *
     * @param userId    已登录用户 id（服务端注入，禁止来自客户端）
     * @param arguments 模型给出的参数（JsonNode）
     * @return 执行结果，ok=false 时走固定拒绝/兜底话术，不回显内部原因
     */
    ToolResult execute(Long userId, JsonNode arguments);
}
