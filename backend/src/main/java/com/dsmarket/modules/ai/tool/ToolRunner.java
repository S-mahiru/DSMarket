package com.dsmarket.modules.ai.tool;

import com.dsmarket.modules.ai.model.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工具执行器：把一次 {@link ToolCall} 变成"喂回模型的可读文本"。
 *
 * <p>非流式工具循环（{@code AiChatService}）与 SSE 流式编排（{@code AiChatStreamService}）
 * 共用同一份执行/折叠语义：ok=false、未注册工具、异常一律折叠为同一句通用话术
 * （内部原因只进日志，侧信道防护 REQ §4.2），避免两处实现漂移。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRunner {

    /** 工具失败折叠话术：不回显内部原因（侧信道防护） */
    public static final String FOLD_TEXT = "该操作未能完成，请换个说法重试，或转人工客服处理。";

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public String feed(Long userId, ToolCall call) {
        ChatTool tool = toolRegistry.get(call.getName());
        if (tool == null) {
            log.warn("[ai] 模型请求了未注册工具: {}", call.getName());
            return FOLD_TEXT;
        }
        try {
            ToolResult r = tool.execute(userId, parseArgs(call.getArguments()));
            if (r.isOk()) {
                return r.getContent();
            }
            // ok=false：内部原因只进日志，不回显给用户
            log.warn("[ai] 工具 {} 执行失败: {}", tool.name(), r.getError());
            return FOLD_TEXT;
        } catch (Exception e) {
            log.error("[ai] 工具 {} 异常", tool.name(), e);
            return FOLD_TEXT;
        }
    }

    /**
     * 参数 JSON → JsonNode（宽松：null/空白/非法一律空对象）。与 {@link #feed} 同一份解析，
     * 供流式编排对 search_knowledge 走 typed 结果前复用，避免两处解析漂移。
     */
    public JsonNode parseArguments(String arguments) {
        return parseArgs(arguments);
    }

    private JsonNode parseArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(arguments);
        } catch (Exception e) {
            log.warn("[ai] 工具参数解析失败，按空参处理: {}", arguments);
            return objectMapper.createObjectNode();
        }
    }
}
