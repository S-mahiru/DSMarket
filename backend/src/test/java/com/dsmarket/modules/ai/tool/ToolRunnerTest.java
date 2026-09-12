package com.dsmarket.modules.ai.tool;

import com.dsmarket.modules.ai.model.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具执行器单测：C5 §2 {@code tools[].ok/reason} 的数据源。
 *
 * <p>{@link ToolRunner#run} 与 {@link ToolRunner#feed} 的关系是"同一份执行、两种呈现"：
 * feed 抹掉原因（侧信道防护，给模型看），run 保留原因（留痕要真实结果）。
 * 本类把这条<b>只在折叠层分叉</b>的契约钉死 —— 两处一旦漂移，
 * 要么模型看见内部原因（侧信道泄漏），要么留痕把所有失败记成同一档（P2 没法按失败原因分层）。</p>
 */
class ToolRunnerTest {

    private static final long USER = 1L;

    private final ObjectMapper om = new ObjectMapper();

    static class Fixed implements ChatTool {
        private final String name;
        private final ToolResult result;

        Fixed(String name, ToolResult result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "固定结果（测试）";
        }

        @Override
        public JsonNode parameters() {
            return null;
        }

        @Override
        public ToolResult execute(Long userId, JsonNode arguments) {
            return result;
        }
    }

    static class Boom implements ChatTool {
        @Override
        public String name() {
            return "boom";
        }

        @Override
        public String description() {
            return "总是抛异常（测试）";
        }

        @Override
        public JsonNode parameters() {
            return null;
        }

        @Override
        public ToolResult execute(Long userId, JsonNode arguments) {
            throw new IllegalStateException("内部炸了");
        }
    }

    private ToolRunner runner(ChatTool... tools) {
        return new ToolRunner(new ToolRegistry(List.of(tools)), om);
    }

    private ToolCall call(String name) {
        return ToolCall.builder().id("c1").name(name).arguments("{}").build();
    }

    @Test
    void ok_returnsRawContent_throughBothPaths() {
        ToolRunner r = runner(new Fixed("echo", ToolResult.builder().ok(true).content("正文").build()));

        assertEquals("正文", r.feed(USER, call("echo")));
        ToolResult raw = r.run(USER, call("echo"));
        assertTrue(raw.isOk());
        assertEquals("正文", raw.getContent());
    }

    @Test
    void failing_reasonKeptInRun_foldedInFeed() {
        ToolRunner r = runner(new Fixed("kb", ToolResult.builder().ok(false).error("LOW_CONF").build()));

        assertEquals("LOW_CONF", r.run(USER, call("kb")).getError(), "留痕要真实原因");
        assertEquals(ToolRunner.FOLD_TEXT, r.feed(USER, call("kb")),
                "喂给模型的文本必须无差别（侧信道防护，REQ §4.2）");
    }

    @Test
    void unknownTool_distinctReasonInRun_sameFoldInFeed() {
        ToolRunner r = runner();

        assertEquals("UNKNOWN_TOOL", r.run(USER, call("nope")).getError());
        assertFalse(r.run(USER, call("nope")).isOk());
        assertEquals(ToolRunner.FOLD_TEXT, r.feed(USER, call("nope")));
    }

    @Test
    void toolException_distinctReasonInRun_sameFoldInFeed() {
        ToolRunner r = runner(new Boom());

        assertEquals("TOOL_EXCEPTION", r.run(USER, call("boom")).getError());
        assertEquals(ToolRunner.FOLD_TEXT, r.feed(USER, call("boom")));
    }

    @Test
    void malformedArguments_doNotBreakExecution() {
        // 参数解析是宽松的（null/空白/非法一律空对象），run 与 feed 共用同一份解析，不会漂移
        ToolRunner r = runner(new Fixed("echo", ToolResult.builder().ok(true).content("OK").build()));
        ToolCall bad = ToolCall.builder().id("c1").name("echo").arguments("{不是 JSON").build();

        assertEquals("OK", r.feed(USER, bad));
        assertEquals("OK", r.run(USER, bad).getContent());
    }
}
