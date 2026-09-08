package com.dsmarket.modules.ai.provider;

import com.dsmarket.modules.ai.model.ChatMessage;
import com.dsmarket.modules.ai.model.ChatResponse;
import com.dsmarket.modules.ai.model.ToolSpec;

import java.util.List;

/**
 * LLM Provider 抽象（OpenAI 兼容 function-calling）。
 *
 * <p>论文口径：LLM 是可替换执行器——换厂商只新增一个 {@link ChatModel} 实现，
 * 智能边界（意图路由/工具边界/检索/置信闸）都在自有层，不依赖具体模型。</p>
 */
public interface ChatModel {

    /** 当前模型名（透传给调用方做记录/展示） */
    String modelName();

    /**
     * 单轮非流式对话。
     *
     * @param messages 完整上下文（含已完成的 tool 结果回合）
     * @param tools    本轮可用的工具声明；可为空表示纯问答
     */
    ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools);
}
