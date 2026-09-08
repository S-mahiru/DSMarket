package com.dsmarket.modules.ai.service;

import com.dsmarket.modules.ai.dto.ChatTurnResult;
import com.dsmarket.modules.ai.model.ChatMessage;
import com.dsmarket.modules.ai.model.ChatResponse;
import com.dsmarket.modules.ai.model.ChatRole;
import com.dsmarket.modules.ai.model.ToolCall;
import com.dsmarket.modules.ai.model.ToolSpec;
import com.dsmarket.modules.ai.provider.ChatModel;
import com.dsmarket.modules.ai.tool.ChatTool;
import com.dsmarket.modules.ai.tool.ToolRegistry;
import com.dsmarket.modules.ai.tool.ToolResult;
import com.dsmarket.modules.ai.tool.ToolRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiChatService 工具循环单测：stub ChatModel + stub ChatTool，不依赖 DB/LLM。
 */
class AiChatServiceTest {

    private static final String FAIL_TEXT = "该操作未能完成，请换个说法重试，或转人工客服处理。";

    /** 按调用次序吐出预设响应的 ChatModel（每轮快照一次 messages，供断言） */
    static class StubChatModel implements ChatModel {
        private final List<ChatResponse> responses;
        private final List<List<ChatMessage>> seen = new ArrayList<>();
        private int idx;

        StubChatModel(ChatResponse... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public String modelName() {
            return "stub";
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
            seen.add(new ArrayList<>(messages));
            ChatResponse r = responses.get(Math.min(idx, responses.size() - 1));
            idx++;
            return r;
        }

        List<ChatMessage> messagesAt(int call) {
            return seen.get(call);
        }
    }

    static class EchoTool implements ChatTool {
        private final boolean ok;
        private final String contentOrError;

        EchoTool(boolean ok, String contentOrError) {
            this.ok = ok;
            this.contentOrError = contentOrError;
        }

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "回显工具（测试用）";
        }

        @Override
        public JsonNode parameters() {
            return null;
        }

        @Override
        public ToolResult execute(Long userId, JsonNode arguments) {
            return ok
                    ? ToolResult.builder().ok(true).content(contentOrError).build()
                    : ToolResult.builder().ok(false).error(contentOrError).build();
        }
    }

    private ToolCall call(String id, String name) {
        return ToolCall.builder().id(id).name(name).arguments("{}").build();
    }

    private AiChatService service(ChatModel model, ChatTool tool) {
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        return new AiChatService(model, registry, new ToolRunner(registry, new ObjectMapper()));
    }

    @Test
    void toolThenFinal_appendsToolResultAndReturnsFinal() {
        ChatResponse toolResp = ChatResponse.builder()
                .toolCalls(List.of(call("c1", "echo")))
                .finishReason("tool_calls").build();
        ChatResponse finalResp = ChatResponse.builder().content("这是最终回答").finishReason("stop").build();
        StubChatModel model = new StubChatModel(toolResp, finalResp);
        EchoTool echo = new EchoTool(true, "回显：42");

        ChatTurnResult result = service(model, echo).handle(100L, "帮我看看");
        assertEquals("这是最终回答", result.getReply());
        assertEquals(List.of("echo"), result.getToolsUsed());

        // 第二次调用时，消息里应带一条 role=TOOL、toolCallId=c1 且内容=工具结果
        List<ChatMessage> atSecond = model.messagesAt(1);
        ChatMessage toolMsg = atSecond.stream()
                .filter(m -> m.getRole() == ChatRole.TOOL)
                .findFirst().orElse(null);
        assertNotNull(toolMsg, "应存在 TOOL 回填消息");
        assertEquals("c1", toolMsg.getToolCallId());
        assertEquals("回显：42", toolMsg.getContent());
    }

    @Test
    void toolFailure_foldedText_noInternalLeak() {
        ChatResponse toolResp = ChatResponse.builder()
                .toolCalls(List.of(call("c1", "echo"))).build();
        ChatResponse finalResp = ChatResponse.builder().content("好的知道了").build();
        StubChatModel model = new StubChatModel(toolResp, finalResp);
        EchoTool broken = new EchoTool(false, "内部机密：连接串口失败");

        ChatTurnResult result = service(model, broken).handle(100L, "帮我看看");
        List<ChatMessage> atSecond = model.messagesAt(1);
        ChatMessage toolMsg = atSecond.stream()
                .filter(m -> m.getRole() == ChatRole.TOOL).findFirst().orElseThrow();
        assertEquals(FAIL_TEXT, toolMsg.getContent(), "失败应折叠为通用话术");
        assertFalse(toolMsg.getContent().contains("机密"), "内部原因不得进入喂回模型的文本");
        assertEquals("好的知道了", result.getReply());
    }

    @Test
    void unknownTool_foldedText() {
        ChatResponse toolResp = ChatResponse.builder()
                .toolCalls(List.of(call("x1", "no_such_tool"))).build();
        ChatResponse finalResp = ChatResponse.builder().content("已处理").build();
        StubChatModel model = new StubChatModel(toolResp, finalResp);

        ChatTurnResult result = service(model, new EchoTool(true, "x")).handle(100L, "hi");
        ChatMessage toolMsg = model.messagesAt(1).stream()
                .filter(m -> m.getRole() == ChatRole.TOOL).findFirst().orElseThrow();
        assertEquals(FAIL_TEXT, toolMsg.getContent());
        assertTrue(result.getToolsUsed().contains("no_such_tool"), "仍应记录模型声称的工具名以便留痕");
    }

    @Test
    void endlessToolCalls_reachesCapAndFallsBack() {
        StubChatModel model = new StubChatModel(
                ChatResponse.builder().toolCalls(List.of(call("c1", "echo"))).build());
        ChatTurnResult result = service(model, new EchoTool(true, "回显")).handle(100L, "一直问工具");

        assertTrue(result.getReply().contains("转人工"), "轮次上限后应兜底，实际=" + result.getReply());
        assertEquals(4, result.getToolsUsed().size(), "应恰好循环到上限后停止");
    }
}
