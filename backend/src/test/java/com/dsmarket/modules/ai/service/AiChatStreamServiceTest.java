package com.dsmarket.modules.ai.service;

import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.ai.config.AiProperties;
import com.dsmarket.modules.ai.context.ChatScenario;
import com.dsmarket.modules.ai.dto.ChatRequest;
import com.dsmarket.modules.ai.limit.ChatRateLimiter;
import com.dsmarket.modules.ai.model.ChatMessage;
import com.dsmarket.modules.ai.model.ChatResponse;
import com.dsmarket.modules.ai.model.ChatRole;
import com.dsmarket.modules.ai.model.ChatRound;
import com.dsmarket.modules.ai.model.ToolCall;
import com.dsmarket.modules.ai.model.ToolSpec;
import com.dsmarket.modules.ai.provider.ChatModel;
import com.dsmarket.modules.ai.search.KnowledgeSearchResult;
import com.dsmarket.modules.ai.search.KnowledgeSegment;
import com.dsmarket.modules.ai.service.KnowledgeSearchService;
import com.dsmarket.modules.ai.session.AiSessionStore;
import com.dsmarket.modules.ai.session.AiSingleFlight;
import com.dsmarket.modules.ai.session.ChatDedupStore;
import com.dsmarket.modules.ai.sse.ChatStream;
import com.dsmarket.modules.ai.tool.ChatTool;
import com.dsmarket.modules.ai.tool.ToolRegistry;
import com.dsmarket.modules.ai.tool.ToolResult;
import com.dsmarket.modules.ai.tool.ToolRunner;
import com.dsmarket.modules.ai.tool.impl.SearchKnowledgeTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiChatStreamService SSE 编排单测（REQ §8 测试前置：注入确定性上游 stub，事件序列可复现）。
 * 覆盖：T1(工具→delta→done)、T6(直接正文)、T8(超轮 error)、T7(上游 fallback→done)、
 * E5/E6(超时 error TIMEOUT)、E2(429)、E12(409)、§2(clientMsgId 回放/登记)、C1-F1(场景注入)、空正文兜底。
 */
class AiChatStreamServiceTest {

    private static final long USER = 100L;
    /** 默认 FAQ stub 回文（FAQ 兜底内容由 KnowledgeFaqFallback 决定，测试注入固定文本） */
    private static final String DEFAULT_FAQ_TEXT = "（自动回复）测试 FAQ 顶命命中。";

    // ------------------------------------------------------------ 内存替身

    static class MemoryStore implements AiSessionStore {
        private final Map<Long, List<ChatRound>> data = new HashMap<>();

        @Override
        public List<ChatRound> loadRounds(Long userId) {
            return new ArrayList<>(data.getOrDefault(userId, new ArrayList<>()));
        }

        @Override
        public void appendRound(Long userId, ChatRound round) {
            data.computeIfAbsent(userId, k -> new ArrayList<>()).add(round);
        }

        List<ChatRound> of(Long userId) {
            return data.getOrDefault(userId, new ArrayList<>());
        }
    }

    static class MemoryFlight implements AiSingleFlight {
        private final boolean busy;
        private int released;

        MemoryFlight(boolean busy) {
            this.busy = busy;
        }

        @Override
        public boolean tryAcquire(Long userId) {
            return !busy;
        }

        @Override
        public void release(Long userId) {
            released++;
        }
    }

    static class StubDedup implements ChatDedupStore {
        private final String replay;
        final List<String[]> marks = new ArrayList<>();

        StubDedup(String replay) {
            this.replay = replay;
        }

        @Override
        public String findReplyId(Long userId, String clientMsgId) {
            return replay;
        }

        @Override
        public void mark(Long userId, String clientMsgId, String replyId) {
            marks.add(new String[]{clientMsgId, replyId});
        }
    }

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

    static class OkTool implements ChatTool {
        private final String content;

        OkTool(String content) {
            this.content = content;
        }

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "回显（测试）";
        }

        @Override
        public JsonNode parameters() {
            return null;
        }

        @Override
        public ToolResult execute(Long userId, JsonNode arguments) {
            return ToolResult.builder().ok(true).content(content).build();
        }
    }

    /** 内存录制流：把事件序列落成可断言的顺序列表 */
    static class RecordingStream implements ChatStream {
        final List<String> order = new ArrayList<>();
        final StringBuilder deltas = new StringBuilder();
        String fallbackContent;
        String suggestReason;
        String doneReplyId;
        String errorCode;
        boolean completed;

        @Override
        public void toolBegin(String tool, String label) {
            order.add("tool_begin:" + tool);
        }

        @Override
        public void delta(String text) {
            order.add("delta");
            deltas.append(text);
        }

        @Override
        public void fallback(String content) {
            order.add("fallback");
            this.fallbackContent = content;
        }

        @Override
        public void suggest(String reason) {
            order.add("suggest");
            this.suggestReason = reason;
        }

        @Override
        public void done(String replyId) {
            order.add("done");
            this.doneReplyId = replyId;
        }

        @Override
        public void error(String code, String message) {
            order.add("error");
            this.errorCode = code;
        }

        @Override
        public void complete() {
            completed = true;
        }
    }

    // ------------------------------------------------------------ 装配

    private ChatRequest req(String content) {
        ChatRequest r = new ChatRequest();
        r.setContent(content);
        return r;
    }

    private AiChatStreamService.Preflight pf(ChatRequest req) {
        return new AiChatStreamService.Preflight(USER, req, false, null);
    }

    /** FAQ stub：固定回文（resolve 由测试注入，绕过真实 mapper） */
    private KnowledgeFaqFallback fixedFaq(String text) {
        return new KnowledgeFaqFallback(null) {
            @Override
            public String resolve(String content) {
                return text;
            }
        };
    }

    private AiChatStreamService service(ChatModel model, AiSessionStore store, AiSingleFlight flight,
                                        ChatTool tool, ChatRateLimiter rate, ChatDedupStore dedup,
                                        ChatScenario scenario) {
        List<ChatTool> tools = tool == null ? List.of() : List.of(tool);
        return service(model, store, flight, tools, rate, dedup, scenario, fixedFaq(DEFAULT_FAQ_TEXT));
    }

    /** 多工具 + 显式 FAQ stub 的装配（knowledge 短路用） */
    private AiChatStreamService service(ChatModel model, AiSessionStore store, AiSingleFlight flight,
                                        List<ChatTool> tools, ChatRateLimiter rate, ChatDedupStore dedup,
                                        ChatScenario scenario, KnowledgeFaqFallback faq) {
        ToolRegistry registry = new ToolRegistry(tools);
        ToolRunner runner = new ToolRunner(registry, new ObjectMapper());
        return new AiChatStreamService(model, runner, registry, store, flight,
                rate, dedup, scenario, new AiProperties(), faq);
    }

    private ChatScenario noScenario() {
        return (u, c) -> "";
    }

    private ChatRateLimiter allow() {
        return u -> true;
    }

    private ToolCall call(String id) {
        return ToolCall.builder().id(id).name("echo").arguments("{}").build();
    }

    private ToolCall knowledgeCall(String id, String argsJson) {
        return ToolCall.builder().id(id).name("search_knowledge").arguments(argsJson).build();
    }

    /** 覆盖的知识检索 stub（covered=true 带一条售后片段） */
    private KnowledgeSearchService coveredSearch(String question, String answer) {
        return q -> {
            KnowledgeSearchResult r = new KnowledgeSearchResult();
            r.setQuery(q);
            r.setCovered(true);
            KnowledgeSegment seg = new KnowledgeSegment();
            seg.setCategory("after_sale");
            seg.setCategoryName("售后服务");
            seg.setQuestion(question);
            seg.setAnswer(answer);
            r.getSegments().add(seg);
            return r;
        };
    }

    /** 低置信的知识检索 stub（covered=false → 短路） */
    private KnowledgeSearchService notCoveredSearch() {
        return q -> {
            KnowledgeSearchResult r = new KnowledgeSearchResult();
            r.setQuery(q);
            r.setCovered(false);
            return r;
        };
    }

    // ------------------------------------------------------------ 用例

    @Test
    void toolThenFinal_emitsToolBeginDeltaDone_andAppendsRoundAndReleases() {
        MemoryStore store = new MemoryStore();
        MemoryFlight flight = new MemoryFlight(false);
        StubChatModel model = new StubChatModel(
                ChatResponse.builder().toolCalls(List.of(call("c1"))).finishReason("tool_calls").build(),
                ChatResponse.builder().content("你的订单号是 BETA。").finishReason("stop").build());
        AiChatStreamService svc = service(model, store, flight, new OkTool("查询结果：BETA 已付款"),
                allow(), new StubDedup(null), noScenario());
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("查我最近订单")), s);

        // 期望事件序列：tool_begin → delta* → done（REQ §3 收尾语法）
        assertEquals(List.of("tool_begin:echo", "delta", "done"), s.order);
        assertEquals("1", s.doneReplyId);
        assertEquals("你的订单号是 BETA。", s.deltas.toString());
        assertEquals(1, store.of(USER).size());
        ChatRound round = store.of(USER).get(0);
        assertEquals("查我最近订单", round.getUserContent());
        assertEquals("你的订单号是 BETA。", round.getAssistantContent());
        assertEquals(1, flight.released, "正常收尾后必须释放单飞行");
        assertTrue(s.completed);
    }

    @Test
    void directContent_noTool_deltaDone() {
        MemoryStore store = new MemoryStore();
        MemoryFlight flight = new MemoryFlight(false);
        StubChatModel model = new StubChatModel(ChatResponse.builder().content("你好！有什么可以帮你？").build());
        AiChatStreamService svc = service(model, store, flight, null, allow(),
                new StubDedup(null), noScenario());
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("你好")), s);

        assertEquals(List.of("delta", "done"), s.order);
        assertEquals("你好！有什么可以帮你？", s.deltas.toString());
        assertEquals(1, store.of(USER).size());
    }

    // ------------------------------------------------------------ 知识短路（切片 3：T4/T5/NO_ARG）

    @Test
    void knowledgeCovered_modelAnswersFromRenderedSegments_deltaDone() {
        MemoryStore store = new MemoryStore();
        MemoryFlight flight = new MemoryFlight(false);
        KnowledgeSearchService ks = coveredSearch("七天无理由退货支持吗", "支持，签收后 7 天内可无理由退货。");
        SearchKnowledgeTool skt = new SearchKnowledgeTool(ks);
        String expectedTool = "【售后服务】七天无理由退货支持吗\n支持，签收后 7 天内可无理由退货。";
        StubChatModel model = new StubChatModel(
                ChatResponse.builder().toolCalls(List.of(knowledgeCall("c1",
                        "{\"query\":\"七天无理由多久能退\"}"))).build(),
                ChatResponse.builder().content("支持七天无理由退货，签收后 7 天内可申请。").build());
        AiChatStreamService svc = service(model, store, flight, List.of(skt), allow(),
                new StubDedup(null), noScenario(), fixedFaq(DEFAULT_FAQ_TEXT));
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("七天无理由退货多久能退")), s);

        // T4 高置信：tool_begin(search_knowledge) → delta* → done
        assertEquals(List.of("tool_begin:search_knowledge", "delta", "done"), s.order);
        assertEquals("支持七天无理由退货，签收后 7 天内可申请。", s.deltas.toString());
        // TOOL 回填必须是 F6 渲染的片段文本（非折叠话术），模型基于它作答
        List<ChatMessage> second = model.messagesAt(1);
        ChatMessage toolMsg = second.get(second.size() - 1);
        assertEquals(ChatRole.TOOL, toolMsg.getRole());
        assertEquals(expectedTool, toolMsg.getContent());
        assertEquals(1, store.of(USER).size());
        assertEquals(1, flight.released);
    }

    @Test
    void knowledgeNotCovered_shortCircuits_fallbackFaqSuggestDone() {
        MemoryStore store = new MemoryStore();
        MemoryFlight flight = new MemoryFlight(false);
        SearchKnowledgeTool skt = new SearchKnowledgeTool(notCoveredSearch());
        StubChatModel model = new StubChatModel(
                ChatResponse.builder().toolCalls(List.of(knowledgeCall("c1",
                        "{\"query\":\"预售商品能否退款\"}"))).build());
        AiChatStreamService svc = service(model, store, flight, List.of(skt), allow(),
                new StubDedup(null), noScenario(), fixedFaq("（自动回复）预售商品请以详情页规则为准。"));
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("预售商品能否退款")), s);

        // T5 低置信：tool_begin(search_knowledge) → fallback(FAQ) → suggest(LOW_CONF) → done，不再问模型
        assertEquals(List.of("tool_begin:search_knowledge", "fallback", "suggest", "done"), s.order);
        assertEquals("（自动回复）预售商品请以详情页规则为准。", s.fallbackContent);
        assertEquals("LOW_CONF", s.suggestReason, "C4-F6 触发① reason=LOW_CONF");
        assertEquals("1", s.doneReplyId);
        assertTrue(s.deltas.length() == 0, "低置信短路不经模型自由发挥，无 delta");
        assertEquals("（自动回复）预售商品请以详情页规则为准。", store.of(USER).get(0).getAssistantContent(),
                "FAQ 兜底文本作为本轮内容写回");
        assertEquals(1, flight.released);
    }

    @Test
    void knowledgeNotCovered_fallbackResolvesToolQuery_notRawUserContent() {
        MemoryStore store = new MemoryStore();
        MemoryFlight flight = new MemoryFlight(false);
        SearchKnowledgeTool skt = new SearchKnowledgeTool(notCoveredSearch());
        StubChatModel model = new StubChatModel(
                ChatResponse.builder().toolCalls(List.of(knowledgeCall("c1",
                        "{\"query\":\"预售商品能否退款\"}"))).build());
        // 记录 FAQ resolve 收到的源文本 → 验证是工具抽取的干净 query，而非含噪音的整句用户内容
        KnowledgeFaqFallback recording = new KnowledgeFaqFallback(null) {
            @Override
            public String resolve(String content) {
                return "R:" + content;
            }
        };
        AiChatStreamService svc = service(model, store, flight, List.of(skt), allow(),
                new StubDedup(null), noScenario(), recording);
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("帮我查一下你们知识库，预售商品能否退款")), s);

        assertEquals(List.of("tool_begin:search_knowledge", "fallback", "suggest", "done"), s.order);
        assertEquals("R:预售商品能否退款", s.fallbackContent,
                "FAQ 兜底优先用工具 query（干净问句），而非含'查一下你们知识库'噪音的整句");
    }

    @Test
    void knowledgeNoArg_asksClarification_deltaDone() {
        MemoryStore store = new MemoryStore();
        MemoryFlight flight = new MemoryFlight(false);
        // NO_ARG 不应触发检索：search 被调用即失败
        SearchKnowledgeTool skt = new SearchKnowledgeTool(q -> {
            throw new AssertionError("NO_ARG 不应检索知识库");
        });
        StubChatModel model = new StubChatModel(
                ChatResponse.builder().toolCalls(List.of(knowledgeCall("c1", "{}"))).build(),
                ChatResponse.builder().content("请问您具体想查哪方面的政策？例如退换货、退款或发货。").build());
        AiChatStreamService svc = service(model, store, flight, List.of(skt), allow(),
                new StubDedup(null), noScenario(), fixedFaq(DEFAULT_FAQ_TEXT));
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("问个事")), s);

        assertEquals(List.of("tool_begin:search_knowledge", "delta", "done"), s.order);
        assertEquals("请问您具体想查哪方面的政策？例如退换货、退款或发货。", s.deltas.toString());
        List<ChatMessage> second = model.messagesAt(1);
        ChatMessage toolMsg = second.get(second.size() - 1);
        assertEquals(ChatRole.TOOL, toolMsg.getRole());
        assertEquals(SearchKnowledgeTool.NO_ARG_TOOL_TEXT, toolMsg.getContent(),
                "NO_ARG 回填澄清引导话术，让模型向用户要具体问题");
        assertEquals(1, store.of(USER).size());
        assertEquals(1, flight.released);
    }

    @Test
    void clientMsgId_present_marksDedupWithReplyId() {
        MemoryStore store = new MemoryStore();
        StubChatModel model = new StubChatModel(ChatResponse.builder().content("答复X").build());
        StubDedup dedup = new StubDedup(null);
        AiChatStreamService svc = service(model, store, new MemoryFlight(false), null,
                allow(), dedup, noScenario());
        RecordingStream s = new RecordingStream();
        ChatRequest r = req("帮我看看");
        r.setClientMsgId("cid-1");

        svc.run(pf(r), s);

        assertEquals(1, dedup.marks.size(), "正常 done 收尾后应登记幂等键");
        assertEquals("cid-1", dedup.marks.get(0)[0]);
        assertEquals("1", dedup.marks.get(0)[1]);
    }

    @Test
    void endlessToolCalls_emitsErrorTooManyToolRounds_noAppend() {
        MemoryStore store = new MemoryStore();
        MemoryFlight flight = new MemoryFlight(false);
        StubChatModel model = new StubChatModel(
                ChatResponse.builder().toolCalls(List.of(call("c1"))).build());
        AiChatStreamService svc = service(model, store, flight, new OkTool("回显"),
                allow(), new StubDedup(null), noScenario());
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("一直问工具")), s);

        assertEquals(3, countToolBegin(s.order), "默认上限 3 轮工具");
        assertEquals(List.of("error"), s.order.subList(3, 4));
        assertEquals(AiChatStreamService.ERR_TOO_MANY_TOOL_ROUNDS, s.errorCode, "E7 错误码");
        assertTrue(store.of(USER).isEmpty(), "error 终止不写回会话");
        assertEquals(1, flight.released);
    }

    @Test
    void upstreamHttpFail_emitsFallbackThenDone_appendsFallback() {
        MemoryStore store = new MemoryStore();
        MemoryFlight flight = new MemoryFlight(false);
        StubChatModel model = new StubChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
                throw new BusinessException(502, "模型上游 HTTP 500");
            }
        };
        AiChatStreamService svc = service(model, store, flight, null, allow(),
                new StubDedup(null), noScenario());
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("查订单")), s);

        assertEquals(List.of("fallback", "done"), s.order, "E4 上游故障 → fallback→done（T7）");
        assertEquals(DEFAULT_FAQ_TEXT, s.fallbackContent, "fallback 内容 = FAQ 兜底解析结果");
        assertEquals("1", s.doneReplyId);
        assertEquals(DEFAULT_FAQ_TEXT, store.of(USER).get(0).getAssistantContent());
        assertEquals(1, flight.released);
    }

    @Test
    void upstreamTimeout_emitsErrorTimeout_noAppend() {
        MemoryStore store = new MemoryStore();
        MemoryFlight flight = new MemoryFlight(false);
        StubChatModel model = new StubChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
                throw new BusinessException(500, "模型调用超时，请稍后重试");
            }
        };
        AiChatStreamService svc = service(model, store, flight, null, allow(),
                new StubDedup(null), noScenario());
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("查订单")), s);

        assertEquals(List.of("error"), s.order);
        assertEquals(AiChatStreamService.ERR_TIMEOUT, s.errorCode, "E5/E6 → error TIMEOUT");
        assertTrue(store.of(USER).isEmpty(), "超时中断不写回");
        assertEquals(1, flight.released);
    }

    @Test
    void blankModelContent_usesEmptyReplyText() {
        MemoryStore store = new MemoryStore();
        StubChatModel model = new StubChatModel(ChatResponse.builder().content("   ").build());
        AiChatStreamService svc = service(model, store, new MemoryFlight(false), null,
                allow(), new StubDedup(null), noScenario());
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("说不清")), s);

        assertEquals(List.of("delta", "done"), s.order);
        assertEquals(AiChatService.EMPTY_REPLY_TEXT, s.deltas.toString());
        assertEquals(AiChatService.EMPTY_REPLY_TEXT, store.of(USER).get(0).getAssistantContent());
    }

    @Test
    void scenarioLine_appendedToSystemMessage() {
        StubChatModel model = new StubChatModel(ChatResponse.builder().content("好了").build());
        ChatScenario scenario = (u, c) -> "【页面上下文】用户正在查看本人订单 O1。";
        AiChatStreamService svc = service(model, new MemoryStore(), new MemoryFlight(false),
                null, allow(), new StubDedup(null), scenario);
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("这单到哪了")), s);

        String system = model.messagesAt(0).get(0).getContent();
        assertTrue(system.contains("【页面上下文】用户正在查看本人订单 O1。"),
                "C1-F1 ③ 场景行应拼进 system，实际=" + system);
    }

    // ------------------------------------------------------------ 预检（开流前 HTTP 状态码）

    @Test
    void open_rateLimited_throws429_beforeFlight() {
        MemoryFlight flight = new MemoryFlight(false);
        AiChatStreamService svc = service(new StubChatModel(), new MemoryStore(), flight,
                null, u -> false, new StubDedup(null), noScenario());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.open(USER, req("hi")));
        assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), ex.getCode(), "E2 限流 → 429");
        assertEquals(0, flight.released, "未获得锁不应释放");
    }

    @Test
    void open_dedupReplay_returnsReplay_withoutAcquiringFlight() {
        MemoryFlight flight = new MemoryFlight(false);
        StubDedup dedup = new StubDedup("7");
        AiChatStreamService svc = service(new StubChatModel(), new MemoryStore(), flight,
                null, allow(), dedup, noScenario());
        ChatRequest r = req("hi");
        r.setClientMsgId("abc");

        AiChatStreamService.Preflight p = svc.open(USER, r);

        assertTrue(p.replay(), "命中幂等 → 回放 done，不进入生成");
        assertEquals("7", p.replayReplyId());
        assertEquals(0, flight.released, "回放不占单飞行");
    }

    @Test
    void open_singleFlightBusy_throws409() {
        MemoryFlight flight = new MemoryFlight(true);
        AiChatStreamService svc = service(new StubChatModel(), new MemoryStore(), flight,
                null, allow(), new StubDedup(null), noScenario());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.open(USER, req("hi")));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode(), "E12 在途 → 409 CONCURRENT_GENERATION");
        assertEquals(0, flight.released, "未获得锁不应释放");
    }

    @Test
    void open_ok_accedesFlightForGeneration() {
        MemoryFlight flight = new MemoryFlight(false);
        AiChatStreamService svc = service(new StubChatModel(), new MemoryStore(), flight,
                null, allow(), new StubDedup(null), noScenario());

        AiChatStreamService.Preflight p = svc.open(USER, req("hi"));
        assertTrue(!p.replay());
        // 生成结束后（run 或 failOpen）释放
        svc.failOpen(p, new RecordingStream());
        assertEquals(1, flight.released, "failOpen 应释放占用的单飞行");
    }

    private int countToolBegin(List<String> order) {
        return (int) order.stream().filter(x -> x.startsWith("tool_begin:")).count();
    }
}
