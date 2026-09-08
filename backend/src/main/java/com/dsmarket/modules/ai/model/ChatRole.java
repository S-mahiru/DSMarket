package com.dsmarket.modules.ai.model;

/**
 * 对话消息角色（与 OpenAI 兼容 role 对齐）。
 */
public enum ChatRole {
    SYSTEM,
    USER,
    ASSISTANT,
    /** 工具执行结果回填 */
    TOOL
}
