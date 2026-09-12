package com.dsmarket.modules.ai.service;

import com.dsmarket.modules.ai.dto.ChatTurnResult;
import com.dsmarket.modules.ai.model.ChatMessage;
import com.dsmarket.modules.ai.model.ChatResponse;
import com.dsmarket.modules.ai.model.ChatRole;
import com.dsmarket.modules.ai.model.ToolCall;
import com.dsmarket.modules.ai.provider.ChatModel;
import com.dsmarket.modules.ai.tool.ToolRegistry;
import com.dsmarket.modules.ai.tool.ToolRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 客服 Agent 编排（工具调用循环）。
 *
 * <p>一次回合（问句 → 最终话术）的骨架：模型可先返回 tool_calls → 服务端逐条执行工具
 * （userId 一律服务端注入）→ 以 TOOL 消息回填 → 继续问模型，直到模型给出最终回答。
 * 达到轮次上限仍未终止则走兜底话术。</p>
 *
 * <p><b>无状态循环内核</b>：本类只保证工具循环正确，不感知会话持久化/SSE/单飞行——
 * 上下文与通道由上层编排（{@link AiSessionService}）负责。单回合调试走 {@link #handle}，
 * 带历史走 {@link #handleMessages}（messages 由会话层拼好，含 system）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    /** 系统提示（边界规则；单一来源，会话层与单回合共用，勿在别处重复拼接） */
    public static final String SYSTEM_PROMPT = """
            你是「黑海商城」的 AI 客服助手。必须遵守：
            1. 只回答与黑海商城购物相关的问题；无关或不确定的，明确说明答不了，不要编造。
            2. 涉及"用户自己的订单"，一律先调用工具 query_my_order 查询，禁止凭空猜测或杜撰订单。
            3. 需要查证时先调用工具拿到结果，再基于工具返回作答；工具说"没有找到"就如实告知用户。
            4. 涉及商城政策/规则/流程（退换货、退款时效、发货、支付、发票、售后、账号等）时，
               先调用工具 search_knowledge 查知识库，并严格基于返回的知识片段作答，不要自行扩展政策内容；
               若工具返回的片段不足以回答，如实告知用户"暂时没有查到该政策"，不要编造。
            5. 一次只回答当前问题，简体中文，简洁、口语化。
            6. 你没有转接人工客服的能力，是否转人工由系统状态决定、不由你宣告。因此绝不能说
               "已为您转接真人/人工""已接入人工客服""已为您联系人工""正在为您转接"这类话——没发生的事不能说。
               用户表达找人工的意愿时，如实告知可以点击"转人工"入口。
            """;

    private static final int MAX_TOOL_TURNS = 4;
    /** 模型异常返回空正文时的兜底（同源供流式编排复用） */
    public static final String EMPTY_REPLY_TEXT = "抱歉，我没有理解你的意思，请换个说法再问一次。";

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    /** 工具执行（含折叠语义）唯一实现，本类与流式编排共用 */
    private final ToolRunner toolRunner;

    /**
     * 单回合（无历史状态）：系统提示 + 用户问句，可能内部多次工具往返。供 M1a 调试与无状态测试用。
     */
    public ChatTurnResult handle(Long userId, String userText) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.builder().role(ChatRole.SYSTEM).content(SYSTEM_PROMPT).build());
        messages.add(ChatMessage.builder().role(ChatRole.USER).content(userText).build());
        return runToolLoop(userId, messages);
    }

    /**
     * 带上下文执行（历史由上层拼好，messages 首条须为 system、末条为当轮 user）。
     * 返回的 {@link ChatTurnResult#getReplyId()} 由上层签发，此处为空。
     */
    public ChatTurnResult handleMessages(Long userId, List<ChatMessage> messages) {
        return runToolLoop(userId, messages);
    }

    private ChatTurnResult runToolLoop(Long userId, List<ChatMessage> messages) {
        List<String> toolsUsed = new ArrayList<>();
        for (int turn = 0; turn < MAX_TOOL_TURNS; turn++) {
            ChatResponse resp = chatModel.chat(messages, toolRegistry.specs());
            List<ToolCall> calls = resp.getToolCalls();
            if (calls == null || calls.isEmpty()) {
                return ChatTurnResult.builder()
                        .reply(blankToFallback(resp.getContent()))
                        .toolsUsed(toolsUsed)
                        .build();
            }
            // 模型本轮要调工具：先把带 tool_calls 的 assistant 消息原样回传
            messages.add(ChatMessage.builder()
                    .role(ChatRole.ASSISTANT)
                    .content(resp.getContent())
                    .toolCalls(calls)
                    .build());
            for (ToolCall call : calls) {
                if (call.getName() == null) {
                    continue;
                }
                toolsUsed.add(call.getName());
                messages.add(ChatMessage.builder()
                        .role(ChatRole.TOOL)
                        .toolCallId(call.getId())
                        .content(toolRunner.feed(userId, call))
                        .build());
            }
        }
        log.warn("[ai] 工具循环达到上限({}轮)仍未收敛，走兜底", MAX_TOOL_TURNS);
        return ChatTurnResult.builder()
                .reply("这个问题我暂时处理不了，建议转人工客服。")
                .toolsUsed(toolsUsed)
                .build();
    }

    private String blankToFallback(String content) {
        return (content == null || content.isBlank()) ? EMPTY_REPLY_TEXT : content;
    }
}
