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
        ToolResult r = run(userId, call);
        if (r.isOk()) {
            return r.getContent();
        }
        // ok=false：内部原因只进日志，不回显给用户
        log.warn("[ai] 工具 {} 执行失败: {}", call.getName(), r.getError());
        return FOLD_TEXT;
    }

    /**
     * 执行一次工具并返回<b>未折叠</b>的结果（不把 ok/reason 抹成同一句 {@link #FOLD_TEXT}）。
     *
     * <p>给 SSE 编排用：C5 §2 的 {@code tools: [{tool, ok, reason?}]} 要记的是真实执行结果，
     * 而 {@link #feed} 的折叠语义恰恰是"抹掉原因"（侧信道防护，给模型看的文本必须无差别）。
     * 两者不能合成一个方法 —— 谁需要真相、谁需要折叠，是调用方的语义，不是执行器的。
     * {@link #feed} 因此改为委托本方法再折叠，两条路径共用同一份执行/解析语义。</p>
     *
     * <p><b>注意别把返回值喂给模型</b>：{@code error} 里含内部原因（如 {@code LOW_CONF}），
     * 直接回填会让模型看见被刻意隐藏的侧信道。</p>
     */
    public ToolResult run(Long userId, ToolCall call) {
        ChatTool tool = toolRegistry.get(call.getName());
        if (tool == null) {
            log.warn("[ai] 模型请求了未注册工具: {}", call.getName());
            return ToolResult.builder().ok(false).error("UNKNOWN_TOOL").build();
        }
        try {
            return tool.execute(userId, parseArgs(call.getArguments()));
        } catch (Exception e) {
            log.error("[ai] 工具 {} 异常", tool.name(), e);
            return ToolResult.builder().ok(false).error("TOOL_EXCEPTION").build();
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
