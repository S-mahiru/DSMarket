package com.dsmarket.modules.ai.service;

import com.dsmarket.common.domain.PageResult;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.ai.config.AiProperties;
import com.dsmarket.modules.ai.context.ChatScenario;
import com.dsmarket.modules.ai.dto.AiIssueVO;
import com.dsmarket.modules.ai.dto.ChatRequest;
import com.dsmarket.modules.ai.dto.IssueAdoptRequest;
import com.dsmarket.modules.ai.eval.AiEvalRecorder;
import com.dsmarket.modules.ai.eval.AiEvalSink;
import com.dsmarket.modules.ai.limit.ChatRateLimiter;
import com.dsmarket.modules.ai.model.ChatMessage;
import com.dsmarket.modules.ai.model.ChatResponse;
import com.dsmarket.modules.ai.model.ChatRole;
import com.dsmarket.modules.ai.model.ChatRound;
import com.dsmarket.modules.ai.model.ToolCall;
import com.dsmarket.modules.ai.model.ToolSpec;
import com.dsmarket.modules.ai.provider.ChatModel;
import com.dsmarket.modules.ai.search.FusionHit;
import com.dsmarket.modules.ai.search.KnowledgeSearchResult;
import com.dsmarket.modules.ai.search.KnowledgeSegment;
import com.dsmarket.modules.ai.service.AiIssueService;
import com.dsmarket.modules.ai.service.KnowledgeSearchService;
import com.dsmarket.modules.ai.session.AiSessionStore;
import com.dsmarket.modules.ai.session.AiSingleFlight;
import com.dsmarket.modules.ai.session.ChatDedupStore;
import com.dsmarket.modules.ai.session.InMemoryUnresolvedSignalCounter;
import com.dsmarket.modules.ai.session.UnresolvedSignalCounter;
import com.dsmarket.modules.ai.sse.ChatStream;
import com.dsmarket.modules.ai.support.ChatHumanRouter;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * C5 留痕捕获 sink（JUnit5 每个用例新建一个测试实例 → 天然每个用例一份干净记录）。
     * 用它而不是读真实文件：断言"关掉开关后一条都不写"时，必须能区分"没写"与"写了空行"。
     */
    private final MemorySink evalSink = new MemorySink();

    // ------------------------------------------------------------ 内存替身

    static class MemorySink implements AiEvalSink {
        final List<String> lines = new ArrayList<>();
        /** true → 每次写入都抛异常，用于验证"写日志失败不影响主流程"（C5 §1） */
        boolean explode;

        @Override
        public void write(String jsonLine) {
            if (explode) {
                throw new IllegalStateException("模拟磁盘写满");
            }
            lines.add(jsonLine);
        }

        /** 解析第 n 条留痕为 Map（仅测试断言用） */
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed(int n, ObjectMapper om) {
            try {
                return om.readValue(lines.get(n), Map.class);
            } catch (Exception e) {
                throw new AssertionError("留痕不是合法 JSON: " + lines.get(n), e);
            }
        }
    }

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
        /** true → 模拟客户端已断开（E10）；默认 false，存量用例行为不变 */
        boolean cancelled;

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

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

    /**
     * C4 切片 3 分流 stub。默认<b>不分流、不接管</b>——存量用例的行为必须一个字节都不变。
     */
    static class StubHumanRouter implements ChatHumanRouter {
        /** 非 null → route 返回它（模拟命中 PH 会话或锚点） */
        ChatHumanRouter.Routed routed;
        /** true → 竞态复查恒判"已转人工" */
        boolean takeover;
        /** &gt;0 → 第 N 次 hasHumanTakeover 起才返回 true（模拟"流式期间"才转人工） */
        int takeoverFromCall;
        int takeoverCalls;
        int routeCalls;

        StubHumanRouter() {
        }

        StubHumanRouter(ChatHumanRouter.Routed routed) {
            this.routed = routed;
        }

        @Override
        public ChatHumanRouter.Routed route(Long userId, String content, String clientMsgId) {
            routeCalls++;
            return routed;
        }

        @Override
        public boolean hasHumanTakeover(Long userId) {
            takeoverCalls++;
            return takeover || (takeoverFromCall > 0 && takeoverCalls >= takeoverFromCall);
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
        return service(model, store, flight, tools, rate, dedup, scenario, faq, new RecordingIssue());
    }

    /** 全量装配（含显式采集 stub，供采集断言） */
    private AiChatStreamService service(ChatModel model, AiSessionStore store, AiSingleFlight flight,
                                        List<ChatTool> tools, ChatRateLimiter rate, ChatDedupStore dedup,
                                        ChatScenario scenario, KnowledgeFaqFallback faq, AiIssueService issue) {
        return service(model, store, flight, tools, rate, dedup, scenario, faq, issue, new StubHumanRouter());
    }

    /** 全量装配 + C4 切片 3 分流 stub（默认不分流/不接管，故存量断言不受影响） */
    private AiChatStreamService service(ChatModel model, AiSessionStore store, AiSingleFlight flight,
                                        List<ChatTool> tools, ChatRateLimiter rate, ChatDedupStore dedup,
                                        ChatScenario scenario, KnowledgeFaqFallback faq, AiIssueService issue,
                                        ChatHumanRouter humanRouter) {
        return service(model, store, flight, tools, rate, dedup, scenario, faq, issue, humanRouter,
                new InMemoryUnresolvedSignalCounter());
    }

    /** 全量装配 + 未解决计数 stub（C4 §4.6 F6 触发③ 用；默认空计数 = 老用例行为不变） */
    private AiChatStreamService service(ChatModel model, AiSessionStore store, AiSingleFlight flight,
                                        List<ChatTool> tools, ChatRateLimiter rate, ChatDedupStore dedup,
                                        ChatScenario scenario, KnowledgeFaqFallback faq, AiIssueService issue,
                                        ChatHumanRouter humanRouter, UnresolvedSignalCounter counter) {
        return service(model, store, flight, tools, rate, dedup, scenario, faq, issue, humanRouter, counter,
                new AiProperties());
    }

    /** 同上，但显式给配置（用于改阈值这类"只调参数不碰代码"的断言） */
    private AiChatStreamService service(ChatModel model, AiSessionStore store, AiSingleFlight flight,
                                        List<ChatTool> tools, ChatRateLimiter rate, ChatDedupStore dedup,
                                        ChatScenario scenario, KnowledgeFaqFallback faq, AiIssueService issue,
                                        ChatHumanRouter humanRouter, UnresolvedSignalCounter counter,
                                        AiProperties props) {
        ToolRegistry registry = new ToolRegistry(tools);
        ToolRunner runner = new ToolRunner(registry, new ObjectMapper());
        return new AiChatStreamService(model, runner, registry, store, flight,
                rate, dedup, scenario, props, faq, issue, humanRouter, counter,
                new AiEvalRecorder(props, evalSink));
    }

    /** C3 采集内存记录 stub（断言采集路径是否触发及参数） */
    static class RecordingIssue implements AiIssueService {
        final List<Long> lowConfUserIds = new ArrayList<>();
        final List<String> lowConfQuestions = new ArrayList<>();
        final List<Long> dislikeUserIds = new ArrayList<>();
        final List<String> dislikeReplyIds = new ArrayList<>();

        @Override
        public void collectLowConfidence(Long userId, String question) {
            lowConfUserIds.add(userId);
            lowConfQuestions.add(question);
        }

        @Override
        public boolean collectDislike(Long userId, String replyId) {
            dislikeUserIds.add(userId);
            dislikeReplyIds.add(replyId);
            return true;
        }

        // 切片 2 复核/兜底方法在会话编排测试不涉及 → 防误用显式抛
        @Override
        public PageResult<AiIssueVO> adminPage(long page, long size, String status, String source) {
            throw new UnsupportedOperationException("not used in chat tests");
        }

        @Override
        public Map<String, Object> adopt(Long id, IssueAdoptRequest request) {
            throw new UnsupportedOperationException("not used in chat tests");
        }

        @Override
        public void ignore(Long id) {
            throw new UnsupportedOperationException("not used in chat tests");
        }

        @Override
        public int retryVectorization() {
            throw new UnsupportedOperationException("not used in chat tests");
        }
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

    /**
     * 覆盖的知识检索 stub（covered=true 带一条售后片段）。
     *
     * <p>候选带两路席位（bm25Rank/denseRank &gt; 0），是为了让 C5 留痕的
     * {@code retrieval.kind} 能派生成 {@code rrf} —— 真机上这里是 RRF 融合出口，
     * stub 不摆席位的话 {@code kind} 会退化成 {@code none}，测出来的就不是线上的口径。</p>
     */
    private KnowledgeSearchService coveredSearch(String question, String answer) {
        return q -> {
            KnowledgeSearchResult r = new KnowledgeSearchResult();
            r.setQuery(q);
            r.setCovered(true);
            r.setTopScore(0.0328);
            KnowledgeSegment seg = new KnowledgeSegment();
            seg.setCategory("after_sale");
            seg.setCategoryName("售后服务");
            seg.setQuestion(question);
            seg.setAnswer(answer);
            r.getSegments().add(seg);
            FusionHit hit = new FusionHit();
            hit.setSegment(seg);
            hit.setFusedScore(0.0328);
            hit.setBm25Rank(1);
            hit.setDenseRank(1);
            r.getCandidates().add(hit);
            return r;
        };
    }

    /**
     * 低置信的知识检索 stub（covered=false → 短路）。
     * 带一条"有席位但没过闸"的候选：这正是 §2 想要的 low 档样本（记下最接近的是什么类目）。
     */
    private KnowledgeSearchService notCoveredSearch() {
        return q -> {
            KnowledgeSearchResult r = new KnowledgeSearchResult();
            r.setQuery(q);
            r.setCovered(false);
            r.setTopScore(0.0071);
            KnowledgeSegment nearMiss = new KnowledgeSegment();
            nearMiss.setCategory("payment");
            nearMiss.setCategoryName("支付服务");
            nearMiss.setQuestion("预售定金能退吗");
            nearMiss.setAnswer("定金规则以商品页为准。");
            FusionHit hit = new FusionHit();
            hit.setSegment(nearMiss);
            hit.setFusedScore(0.0071);
            hit.setBm25Rank(1);
            hit.setDenseRank(0);
            r.getCandidates().add(hit);
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

    // ------------------------------------------------------------ C4 §4.6 F6 触发③（未解决≥2）

    /** 正常答复轮（无工具）的装配，带可驱动的未解决计数 */
    private AiChatStreamService unresolvedSvc(InMemoryUnresolvedSignalCounter counter) {
        StubChatModel model = new StubChatModel(ChatResponse.builder().content("好的，已为您处理。").build());
        return service(model, new MemoryStore(), new MemoryFlight(false), List.of(), allow(),
                new StubDedup(null), noScenario(), fixedFaq(DEFAULT_FAQ_TEXT), new RecordingIssue(),
                new StubHumanRouter(), counter);
    }

    @Test
    void unresolved_reachesThreshold_normalRound_appendsSuggest() {
        // DECISION D20/D21：③ 的**唯一真增量是点踩** —— 点踩走普通 HTTP，那一轮的 SSE 早就关了，
        // 所以必须有"正常答复轮也检查计数"这条路径，否则"点踩两次 → 再问一句"永远看不到气泡。
        InMemoryUnresolvedSignalCounter counter = new InMemoryUnresolvedSignalCounter();
        counter.preset(USER, 2);
        RecordingStream s = new RecordingStream();

        unresolvedSvc(counter).run(pf(req("那到底什么时候发货")), s);

        assertEquals(List.of("delta", "suggest", "done"), s.order,
                "建议必须在正文之后、done 之前（C1 §3 的事件顺序）");
        assertEquals("UNRESOLVED", s.suggestReason);
        assertEquals("1", s.doneReplyId, "气泡不改变本轮收尾：replyId 照常下发，轮次照常写回");
    }

    @Test
    void unresolved_belowThreshold_normalRound_noSuggest() {
        // 恰好 1 次（阈值 2）→ 不发。这条与下一条一起把边界钉在 "≥2" 上。
        InMemoryUnresolvedSignalCounter counter = new InMemoryUnresolvedSignalCounter();
        counter.preset(USER, 1);
        RecordingStream s = new RecordingStream();

        unresolvedSvc(counter).run(pf(req("那到底什么时候发货")), s);

        assertEquals(List.of("delta", "done"), s.order, "1 < 2，不该发气泡");
        assertEquals(null, s.suggestReason);
    }

    @Test
    void unresolved_zero_normalRound_noSuggest() {
        InMemoryUnresolvedSignalCounter counter = new InMemoryUnresolvedSignalCounter();
        RecordingStream s = new RecordingStream();

        unresolvedSvc(counter).run(pf(req("你好")), s);

        assertEquals(List.of("delta", "done"), s.order);
        assertEquals(null, s.suggestReason, "老用例行为必须一个字节不变：没信号就没气泡");
    }

    @Test
    void unresolved_thresholdIsConfigurable_byDefaultTwo() {
        // §4.6 触发条件标了"均可调"：口径收敛只改配置（ai.support.unresolved-threshold），不碰代码。
        assertEquals(2, new AiProperties().getSupport().getUnresolvedThreshold(),
                "缺省口径 = §4.6 原文的 ≥2 次");
    }

    @Test
    void unresolved_thresholdRaisedToThree_noSuggestAtTwo() {
        // 把阈值调到 3 → 攒到 2 就不该发。证明读的确实是配置值而非硬编码常量。
        InMemoryUnresolvedSignalCounter counter = new InMemoryUnresolvedSignalCounter();
        counter.preset(USER, 2);
        AiProperties props = new AiProperties();
        props.getSupport().setUnresolvedThreshold(3);
        StubChatModel model = new StubChatModel(ChatResponse.builder().content("好的。").build());
        AiChatStreamService svc = service(model, new MemoryStore(), new MemoryFlight(false), List.of(), allow(),
                new StubDedup(null), noScenario(), fixedFaq(DEFAULT_FAQ_TEXT), new RecordingIssue(),
                new StubHumanRouter(), counter, props);
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("那到底什么时候发货")), s);

        assertEquals(List.of("delta", "done"), s.order, "阈值 3 时攒到 2 不发（不是硬编码 2）");
    }

    @Test
    void unresolved_thresholdZero_disablesFeature() {
        // 阈值 ≤0 = 关闭该触发（演示/灰度时能一键关掉），不必改代码
        InMemoryUnresolvedSignalCounter counter = new InMemoryUnresolvedSignalCounter();
        counter.preset(USER, 9);
        AiProperties props = new AiProperties();
        props.getSupport().setUnresolvedThreshold(0);
        StubChatModel model = new StubChatModel(ChatResponse.builder().content("好的。").build());
        AiChatStreamService svc = service(model, new MemoryStore(), new MemoryFlight(false), List.of(), allow(),
                new StubDedup(null), noScenario(), fixedFaq(DEFAULT_FAQ_TEXT), new RecordingIssue(),
                new StubHumanRouter(), counter, props);
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("那到底什么时候发货")), s);

        assertEquals(List.of("delta", "done"), s.order, "阈值 0 = 关闭触发③");
    }

    @Test
    void unresolved_lowConfRound_incrementsButDoesNotAddSecondSuggest() {
        // 低置信轮：① 已经占了本轮唯一的名额（C1 §3「fallback 后至多一条」），
        // 且该轮**本身就是**一次未解决 → 计数 +1，但不追加 UNRESOLVED 气泡。
        InMemoryUnresolvedSignalCounter counter = new InMemoryUnresolvedSignalCounter();
        counter.preset(USER, 5);
        SearchKnowledgeTool skt = new SearchKnowledgeTool(notCoveredSearch());
        StubChatModel model = new StubChatModel(
                ChatResponse.builder().toolCalls(List.of(knowledgeCall("c1",
                        "{\"query\":\"预售商品能否退款\"}"))).build());
        AiChatStreamService svc = service(model, new MemoryStore(), new MemoryFlight(false), List.of(skt),
                allow(), new StubDedup(null), noScenario(), fixedFaq(DEFAULT_FAQ_TEXT), new RecordingIssue(),
                new StubHumanRouter(), counter);
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("预售商品能否退款")), s);

        assertEquals(List.of("tool_begin:search_knowledge", "fallback", "suggest", "done"), s.order,
                "只有一条 suggest");
        assertEquals("LOW_CONF", s.suggestReason, "低置信轮发的是触发① 的 LOW_CONF，不是 UNRESOLVED");
        assertEquals(6, counter.count(USER), "本轮自身算一次未解决 → 计数自增");
    }

    @Test
    void unresolved_upstreamFallback_suggestsButDoesNotIncrement() {
        // 上游故障兜底：不是"答不好"而是"上游挂了"→ **不计数**；但买家此前攒够的计数依然有效，
        // 该给出口还是给（否则一次上游抖动就把买家的出口吞了）。
        InMemoryUnresolvedSignalCounter counter = new InMemoryUnresolvedSignalCounter();
        counter.preset(USER, 2);
        StubChatModel upModel = new StubChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
                throw new BusinessException(502, "模型上游 HTTP 500");
            }
        };
        AiChatStreamService svc = service(upModel, new MemoryStore(), new MemoryFlight(false), List.of(),
                allow(), new StubDedup(null), noScenario(), fixedFaq(DEFAULT_FAQ_TEXT), new RecordingIssue(),
                new StubHumanRouter(), counter);
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("查订单")), s);

        assertEquals(List.of("fallback", "suggest", "done"), s.order);
        assertEquals("UNRESOLVED", s.suggestReason);
        assertEquals(2, counter.count(USER), "上游故障不计未解决信号");
    }

    @Test
    void unresolved_takeoverDuringRound_noSuggest() {
        // 竞态：本轮生成期间买家已转人工 → 不下发 suggest（主 REQ §4.2 规则 11 / P16）。
        // 计时：检查点① 在 fallback/正文之前，所以这里连 delta 都不会有。
        InMemoryUnresolvedSignalCounter counter = new InMemoryUnresolvedSignalCounter();
        counter.preset(USER, 3);
        StubChatModel model = new StubChatModel(ChatResponse.builder().content("好的。").build());
        StubHumanRouter router = new StubHumanRouter();
        router.takeover = true;
        AiChatStreamService svc = service(model, new MemoryStore(), new MemoryFlight(false), List.of(), allow(),
                new StubDedup(null), noScenario(), fixedFaq(DEFAULT_FAQ_TEXT), new RecordingIssue(),
                router, counter);
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("那到底什么时候发货")), s);

        assertTrue(s.suggestReason == null, "已转人工时不得再下发任何 suggest（含 UNRESOLVED）");
        assertTrue(s.order.isEmpty(), "检查点① 命中 → 干净作废，一个事件都不发");
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
    void lowConfidenceShortCircuit_collectsIssue_withCleanQuery() {
        MemoryStore store = new MemoryStore();
        MemoryFlight flight = new MemoryFlight(false);
        RecordingIssue issue = new RecordingIssue();
        SearchKnowledgeTool skt = new SearchKnowledgeTool(notCoveredSearch());
        StubChatModel model = new StubChatModel(
                ChatResponse.builder().toolCalls(List.of(knowledgeCall("c1",
                        "{\"query\":\"预售商品能否退款\"}"))).build());
        AiChatStreamService svc = service(model, store, flight, List.of(skt), allow(),
                new StubDedup(null), noScenario(), fixedFaq("（自动回复）预售商品请以详情页规则为准。"), issue);
        RecordingStream s = new RecordingStream();

        // 用户整句带"查一下你们知识库"噪音；采集问句应取工具抽取的干净 query（同 FAQ 兜底源）
        svc.run(pf(req("帮我查一下你们知识库，预售商品能否退款")), s);

        assertEquals(1, issue.lowConfQuestions.size(), "低置信短路应触发 1 次问题池采集");
        assertEquals(USER, issue.lowConfUserIds.get(0));
        assertEquals("预售商品能否退款", issue.lowConfQuestions.get(0),
                "采集问句 = 干净问句（无'查一下你们知识库'指令噪音）");
        assertTrue(issue.dislikeReplyIds.isEmpty(), "chat 流程不触发点踩采集");
        assertEquals(List.of("tool_begin:search_knowledge", "fallback", "suggest", "done"), s.order,
                "采集不改变 SSE 事件协议");
    }

    @Test
    void coveredHighConf_orUpstreamFallback_doNotCollectIssue() {
        // 覆盖两路"非低置信"：COVERED 正常答 & 上游故障 fallback（lowConf=false）都不应采集
        RecordingIssue covered = new RecordingIssue();
        SearchKnowledgeTool skt = new SearchKnowledgeTool(coveredSearch("七天无理由退货支持吗", "支持。"));
        StubChatModel model = new StubChatModel(
                ChatResponse.builder().toolCalls(List.of(knowledgeCall("c1",
                        "{\"query\":\"七天无理由多久能退\"}"))).build(),
                ChatResponse.builder().content("支持七天无理由退货。").build());
        AiChatStreamService svc = service(model, new MemoryStore(), new MemoryFlight(false), List.of(skt),
                allow(), new StubDedup(null), noScenario(), fixedFaq(DEFAULT_FAQ_TEXT), covered);
        svc.run(pf(req("七天无理由退货多久能退")), new RecordingStream());
        assertTrue(covered.lowConfQuestions.isEmpty(), "高置信 COVERED 不应采集");

        RecordingIssue up = new RecordingIssue();
        StubChatModel upModel = new StubChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
                throw new BusinessException(502, "模型上游 HTTP 500");
            }
        };
        AiChatStreamService upSvc = service(upModel, new MemoryStore(), new MemoryFlight(false), List.of(),
                allow(), new StubDedup(null), noScenario(), fixedFaq(DEFAULT_FAQ_TEXT), up);
        upSvc.run(pf(req("查订单")), new RecordingStream());
        assertTrue(up.lowConfQuestions.isEmpty(), "上游故障 fallback（lowConf=false）不应采集（T7 非 LOW_CONF 触发）");
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

    // ------------------------------------------------------------ C4 切片 3：入口分流 + 在途打断

    @Test
    void open_routedToHuman_winsOverRateLimitAndFlight() {
        // 顺序即语义（主 REQ §2.1）：分流排在限流与单飞行**之前**——人工态消息不吃 /chat 额度，
        // 也不该被在途 AI 生成挡住。这里限流拒、单飞行占用，两者都必须让位于分流。
        MemoryFlight busy = new MemoryFlight(true);
        StubHumanRouter router = new StubHumanRouter(
                new ChatHumanRouter.Routed(7L, "pending_human", "已转人工"));
        AiChatStreamService svc = service(new StubChatModel(), new MemoryStore(), busy, List.of(),
                u -> false, new StubDedup(null), noScenario(), fixedFaq(DEFAULT_FAQ_TEXT),
                new RecordingIssue(), router);

        AiChatStreamService.Preflight p = svc.open(USER, req("我要人工客服"));

        assertTrue(p.routedToHuman(), "命中分流 → 不进 AI 态");
        assertTrue(!p.replay(), "分流轮不是幂等回放");
        assertEquals(7L, p.human().sessionId());
        assertEquals("pending_human", p.human().status());
        assertEquals(1, router.routeCalls);
        assertEquals(0, busy.released, "分流轮不占单飞行，故也不释放");
    }

    @Test
    void open_aiStateContentOverChatMax_isRejected400() {
        // C1 §2 分层长度：走到这里说明既非 PH 也没命中锚点 → 按 AI 态上限（500）判
        AiChatStreamService svc = service(new StubChatModel(), new MemoryStore(), new MemoryFlight(false),
                null, allow(), new StubDedup(null), noScenario());

        BusinessException ex = assertThrows(BusinessException.class, () -> svc.open(USER, req("发".repeat(501))));

        assertEquals(ErrorCode.BAD_REQUEST.getCode(), ex.getCode(), "超 500 且未命中锚点 → 400");
        assertTrue(ex.getMessage().contains("500"), "提示要带出真实上限，便于排查");
    }

    @Test
    void open_contentExactlyAtChatMax_isAccepted() {
        // 边界：正好 500 不算超（> 而非 >=）。放宽口径时最容易被顺手改错的地方
        AiChatStreamService svc = service(new StubChatModel(), new MemoryStore(), new MemoryFlight(false),
                null, allow(), new StubDedup(null), noScenario());

        assertTrue(!svc.open(USER, req("发".repeat(500))).routedToHuman(), "500 字整应放行进 AI");
    }

    @Test
    void open_anchorHitLongContent_isExemptFromChatMax() {
        // C1 §2 明文"锚点检测先于长度校验"：命中锚点的长申诉必须能走到分流口，不能被 500 挡掉。
        // 这正是长度校验**排在 route() 之后**的原因——排前面就把"长申诉可达转人工口子"堵死了。
        StubHumanRouter router = new StubHumanRouter(
                new ChatHumanRouter.Routed(3L, "pending_human", "已转人工"));
        AiChatStreamService svc = service(new StubChatModel(), new MemoryStore(), new MemoryFlight(false),
                List.of(), allow(), new StubDedup(null), noScenario(), fixedFaq(DEFAULT_FAQ_TEXT),
                new RecordingIssue(), router);
        ChatRequest longGrievance = req("我要投诉".repeat(150)); // 600 字 > 500，且含锚点

        AiChatStreamService.Preflight p = svc.open(USER, longGrievance);

        assertTrue(p.routedToHuman(), "锚点豁免 500：长申诉照样进分流口");
        assertEquals(1, router.routeCalls);
    }

    @Test
    void run_humanRouted_neverTouchesModel() {
        // 不只是"调了不用"——分流轮必须连模型都不碰
        StubChatModel model = new StubChatModel(ChatResponse.builder().content("不该出现").build());
        AiChatStreamService svc = service(model, new MemoryStore(), new MemoryFlight(false), List.of(),
                allow(), new StubDedup(null), noScenario(), fixedFaq(DEFAULT_FAQ_TEXT),
                new RecordingIssue(), new StubHumanRouter());
        AiChatStreamService.Preflight p = new AiChatStreamService.Preflight(USER, req("hi"), false, null,
                new ChatHumanRouter.Routed(9L, "human_active", "已转人工"));
        RecordingStream s = new RecordingStream();

        svc.run(p, s);

        assertEquals(List.of(), s.order, "分流轮不产生任何 AI 事件（提示由 controller 发）");
        assertTrue(s.completed);
        assertEquals(0, model.seen.size(), "不得调用模型");
    }

    @Test
    void takeoverBeforeEmit_discardsRound_noEventsNoWriteBack() {
        // 竞态检查点①：模型调用是阻塞的，这几秒里买家从别的入口转了人工。
        // 此刻一个字节都还没发，是唯一能"干净作废"的位置（P16 取消在途流）。
        MemoryStore store = new MemoryStore();
        MemoryFlight flight = new MemoryFlight(false);
        StubHumanRouter router = new StubHumanRouter();
        router.takeover = true;
        StubChatModel model = new StubChatModel(ChatResponse.builder().content("不该发出的回答").build());
        AiChatStreamService svc = service(model, store, flight, List.of(), allow(), new StubDedup(null),
                noScenario(), fixedFaq(DEFAULT_FAQ_TEXT), new RecordingIssue(), router);
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("hi")), s);

        assertEquals(List.of(), s.order, "连 done 都不发：该轮没有正常结束可言");
        assertEquals(0, store.of(USER).size(), "不回写被打断的半截轮");
        assertEquals(1, model.seen.size(), "模型确实调过了——上游阻塞不可真中断，只能返回后丢弃");
        assertEquals(1, flight.released, "finally 仍须释放单飞行");
    }

    @Test
    void takeoverDuringStreaming_deltasOutButNoWriteBack() {
        // 竞态检查点②（C1 §4.1「写回前复查会话状态」）：流式期间才转人工。
        // 诚实边界：delta 已经推给买家了，SSE 发出去收不回 —— 能保证的是不回写。
        MemoryStore store = new MemoryStore();
        StubHumanRouter router = new StubHumanRouter();
        router.takeoverFromCall = 2; // 第 1 次（下发前）放过，第 2 次（写回前）判定已转人工
        StubChatModel model = new StubChatModel(ChatResponse.builder().content("流到一半的回答").build());
        AiChatStreamService svc = service(model, store, new MemoryFlight(false), List.of(), allow(),
                new StubDedup(null), noScenario(), fixedFaq(DEFAULT_FAQ_TEXT), new RecordingIssue(), router);
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("hi")), s);

        assertEquals(List.of("delta"), s.order, "delta 已发出（不可撤回），但不再有 done");
        assertEquals("流到一半的回答", s.deltas.toString());
        assertEquals(0, store.of(USER).size(), "不回写本半截回答");
        assertEquals(2, router.takeoverCalls, "下发前与写回前各复查一次");
    }

    @Test
    void takeoverDuringFaqResolve_skipsSuggestAndCollect() {
        // 低置信兜底路径的检查点②：faqFallback.resolve 是一次真实检索（有耗时），
        // 期间转人工 → 不下发 suggest、不采集（主 REQ §4.2 规则 11 明确列出这两项），也不写回。
        MemoryStore store = new MemoryStore();
        StubHumanRouter router = new StubHumanRouter();
        router.takeoverFromCall = 2;
        RecordingIssue issue = new RecordingIssue();
        SearchKnowledgeTool skt = new SearchKnowledgeTool(notCoveredSearch());
        StubChatModel model = new StubChatModel(ChatResponse.builder()
                .toolCalls(List.of(knowledgeCall("c1", "{\"query\":\"预售商品能否退款\"}"))).build());
        AiChatStreamService svc = service(model, store, new MemoryFlight(false), List.of(skt), allow(),
                new StubDedup(null), noScenario(), fixedFaq(DEFAULT_FAQ_TEXT), issue, router);
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("预售商品能否退款")), s);

        assertEquals(List.of("tool_begin:search_knowledge", "fallback"), s.order,
                "兜底文案已发出，但 suggest 与 done 都不该出现");
        assertEquals(0, issue.lowConfQuestions.size(), "被打断轮不采集问题池");
        assertEquals(0, store.of(USER).size(), "不回写");
    }

    // ------------------------------------------------------------ C5 切片 1：AI 轮留痕（REQ-20260908-C5 §2）

    private static final ObjectMapper OM = new ObjectMapper();

    /**
     * §2 必含键（REQ 原文 13 个，逐条对齐）。
     * 这 13 个键是 §4.4「留痕完整性验收」的判据，缺任意一个该轮就算异常 —— 所以这里逐个断言
     * 而不是"检查有没有 content 就行"：漏掉 suggestSent 这种，重放脚本会算出错的建议触发率。
     */
    private static final List<String> REQUIRED_KEYS = List.of(
            "ts", "userIdHash", "replyId", "roundId", "content", "intent", "tools", "retrieval",
            "replyKind", "suggestSent", "eventSeq", "firstDeltaMs", "outcome");

    /** 取唯一那条留痕并校验"恰好一条"—— 一轮多写/漏写都是静默偏差，必须每例都验 */
    private Map<String, Object> onlyRecord() {
        assertEquals(1, evalSink.lines.size(),
                "一轮 AI 对话必须恰好留一条痕（实际 " + evalSink.lines.size() + " 条）");
        Map<String, Object> rec = evalSink.parsed(0, OM);
        for (String k : REQUIRED_KEYS) {
            assertTrue(rec.containsKey(k), "§2 必含键缺失: " + k + "；实到键=" + rec.keySet());
        }
        assertEquals("ai_round", rec.get("kind"));
        return rec;
    }

    private long millis(Map<String, Object> rec) {
        return ((Number) rec.get("firstDeltaMs")).longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapAt(Map<String, Object> rec, String key) {
        return (Map<String, Object>) rec.get(key);
    }

    @Test
    void eval_normalRound_oneRecord_derivedIntentAndEventSeq() {
        StubChatModel model = new StubChatModel(ChatResponse.builder().content("你好！有什么可以帮你？").build());
        AiChatStreamService svc = service(model, new MemoryStore(), new MemoryFlight(false), null,
                allow(), new StubDedup(null), noScenario());
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("你好")), s);

        Map<String, Object> rec = onlyRecord();
        assertEquals("ai", rec.get("replyKind"));
        assertEquals("1", rec.get("replyId"), "done 签发了 replyId → 记真实值");
        assertEquals("你好", rec.get("content"), "记的是问句原文（§2 content）");
        assertEquals("DIRECT_ANSWER", rec.get("intent"), "无工具 → 直答（从工具序列派生，非独立分类器）");
        assertEquals(List.of(), rec.get("tools"));
        assertEquals("none", mapAt(rec, "retrieval").get("kind"),
                "没发生检索 → kind=none 的对象（§2 把 retrieval 定成 object），不是 null");
        assertNull(mapAt(rec, "retrieval").get("conf"), "conf=null ⇒ 压根没调检索工具");
        assertEquals(false, rec.get("suggestSent"));
        assertEquals("d,done", rec.get("eventSeq"));
        assertTrue(millis(rec) >= 0, "正常轮首包时延应已测到，实际=" + millis(rec));
        assertTrue(((Number) rec.get("ts")).longValue() > 0);
        String hash = (String) rec.get("userIdHash");
        assertEquals(16, hash.length(), "userIdHash 取 HMAC 前 8 字节 = 16 个十六进制字符");
        assertTrue(!hash.contains(String.valueOf(USER)), "§2 明令不用明文 userId，哈希里也不该能看出 100");
        assertEquals(List.of(), new ArrayList<>(mapAt(rec, "outcome").keySet()),
                "§2 outcome 线上恒为空对象，由 §4.2 的标注 CSV 离线 join 回来");
    }

    @Test
    void eval_toolRound_recordsToolOutcomeAndToolBeginInSeq() {
        StubChatModel model = new StubChatModel(
                ChatResponse.builder().toolCalls(List.of(call("c1"))).finishReason("tool_calls").build(),
                ChatResponse.builder().content("你的订单号是 BETA。").finishReason("stop").build());
        AiChatStreamService svc = service(model, new MemoryStore(), new MemoryFlight(false),
                new OkTool("查询结果：BETA 已付款"), allow(), new StubDedup(null), noScenario());

        svc.run(pf(req("查我最近订单")), new RecordingStream());

        Map<String, Object> rec = onlyRecord();
        assertEquals("ai", rec.get("replyKind"));
        assertEquals("OTHER", rec.get("intent"), "echo 不在已知映射里 → OTHER（不静默归错类）");
        assertEquals("tool_begin,d,done", rec.get("eventSeq"));
        List<Map<String, Object>> tools = (List<Map<String, Object>>) rec.get("tools");
        assertEquals(1, tools.size());
        assertEquals("echo", tools.get(0).get("tool"));
        assertEquals(true, tools.get(0).get("ok"));
        assertTrue(!tools.get(0).containsKey("reason"), "ok=true 时不写 reason（§2 的 reason? 是可选项）");
    }

    @Test
    void eval_failingTool_recordsOkFalseWithReason() {
        StubChatModel model = new StubChatModel(
                ChatResponse.builder().toolCalls(List.of(ToolCall.builder()
                        .id("c1").name("boom").arguments("{}").build())).build(),
                ChatResponse.builder().content("换个说法吧。").build());
        AiChatStreamService svc = service(model, new MemoryStore(), new MemoryFlight(false),
                List.<ChatTool>of(new FailTool()), allow(), new StubDedup(null), noScenario(),
                fixedFaq(DEFAULT_FAQ_TEXT));

        svc.run(pf(req("试试失败工具")), new RecordingStream());

        List<Map<String, Object>> tools = (List<Map<String, Object>>) onlyRecord().get("tools");
        assertEquals(false, tools.get(0).get("ok"));
        assertEquals("TOOL_DOWN", tools.get(0).get("reason"),
                "留痕记的是未折叠的真实原因（喂给模型的文本仍折成 FOLD_TEXT）");
    }

    @Test
    void eval_lowConfRound_replyKindFaq_withRetrievalLowConf() {
        SearchKnowledgeTool skt = new SearchKnowledgeTool(notCoveredSearch());
        StubChatModel model = new StubChatModel(
                ChatResponse.builder().toolCalls(List.of(knowledgeCall("c1",
                        "{\"query\":\"预售商品能否退款\"}"))).build());
        AiChatStreamService svc = service(model, new MemoryStore(), new MemoryFlight(false), List.of(skt),
                allow(), new StubDedup(null), noScenario(), fixedFaq(DEFAULT_FAQ_TEXT));

        svc.run(pf(req("预售商品能否退款")), new RecordingStream());

        Map<String, Object> rec = onlyRecord();
        assertEquals("faq", rec.get("replyKind"), "低置信短路走的是 FAQ 兜底档（§2 faq）");
        assertEquals("LOW_CONF", rec.get("suggestReason"));
        assertEquals(true, rec.get("suggestSent"));
        assertEquals("tool_begin,fallback,suggest,done", rec.get("eventSeq"));
        assertEquals("KNOWLEDGE_QA", rec.get("intent"), "首个工具是 search_knowledge");
        assertTrue(millis(rec) >= 0, "兜底轮没有 delta，firstDeltaMs 由 fallback 事件测（§2 原文）");
        Map<String, Object> ret = mapAt(rec, "retrieval");
        assertEquals("bm25", ret.get("kind"), "只有 BM25 路有席位（stub：denseRank=0）");
        assertEquals("low", ret.get("conf"));
        assertEquals("payment", ret.get("topCategory"), "低置信时回退记'最接近的类目'，这是分析阈值的唯一线索");
    }

    @Test
    void eval_coveredToolRound_retrievalKindRrfHigh() {
        SearchKnowledgeTool skt = new SearchKnowledgeTool(coveredSearch("七天无理由退货支持吗", "支持。"));
        StubChatModel model = new StubChatModel(
                ChatResponse.builder().toolCalls(List.of(knowledgeCall("c1",
                        "{\"query\":\"七天无理由多久能退\"}"))).build(),
                ChatResponse.builder().content("支持七天无理由退货。").build());
        AiChatStreamService svc = service(model, new MemoryStore(), new MemoryFlight(false), List.of(skt),
                allow(), new StubDedup(null), noScenario(), fixedFaq(DEFAULT_FAQ_TEXT));

        svc.run(pf(req("七天无理由退货多久能退")), new RecordingStream());

        Map<String, Object> ret = mapAt(onlyRecord(), "retrieval");
        assertEquals("rrf", ret.get("kind"), "两路都有席位 → rrf");
        assertEquals("high", ret.get("conf"));
        assertEquals("after_sale", ret.get("topCategory"));
    }

    @Test
    void eval_upstreamFailure_replyKindFallback_notFaq() {
        // faq 与 fallback 都发 fallback 事件、都经 faqFallback 取文，差别只在**为什么**走到这条路上：
        // 前者该补知识，后者该修链路。混成一档就没法从日志区分这两件事（§2 分列两档的用意）。
        StubChatModel model = new StubChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
                throw new BusinessException(502, "模型上游 HTTP 500");
            }
        };
        AiChatStreamService svc = service(model, new MemoryStore(), new MemoryFlight(false), List.of(),
                allow(), new StubDedup(null), noScenario(), fixedFaq(DEFAULT_FAQ_TEXT));

        svc.run(pf(req("查订单")), new RecordingStream());

        Map<String, Object> rec = onlyRecord();
        assertEquals("fallback", rec.get("replyKind"));
        assertEquals("fallback,done", rec.get("eventSeq"));
        assertEquals(false, rec.get("suggestSent"), "上游故障兜底不发 suggest（C4 只给低置信轮发）");
        assertNull(mapAt(rec, "retrieval").get("conf"), "上游故障根本没走到检索 → conf=null");
    }

    @Test
    void eval_timeout_replyKindError_emptyReplyIdAndNoLatency() {
        StubChatModel model = new StubChatModel() {
            @Override
            public ChatResponse chat(List<ChatMessage> messages, List<ToolSpec> tools) {
                throw new BusinessException(500, "模型调用超时，请稍后重试");
            }
        };
        AiChatStreamService svc = service(model, new MemoryStore(), new MemoryFlight(false), null,
                allow(), new StubDedup(null), noScenario());

        svc.run(pf(req("查订单")), new RecordingStream());

        Map<String, Object> rec = onlyRecord();
        assertEquals("error", rec.get("replyKind"));
        assertEquals("TIMEOUT", rec.get("errorCode"));
        assertEquals("error", rec.get("eventSeq"));
        assertEquals("", rec.get("replyId"), "§2：无 done → replyId 记空，靠 roundId 标识本轮");
        assertEquals(-1L, millis(rec), "没有内容事件记 -1，不用 0 —— 0 会冒充'瞬时返回'拉低首包时延均值");
    }

    @Test
    void eval_toolRoundLimit_replyKindError() {
        StubChatModel model = new StubChatModel(
                ChatResponse.builder().toolCalls(List.of(call("c1"))).build());
        AiChatStreamService svc = service(model, new MemoryStore(), new MemoryFlight(false),
                new OkTool("回显"), allow(), new StubDedup(null), noScenario());

        svc.run(pf(req("一直问工具")), new RecordingStream());

        Map<String, Object> rec = onlyRecord();
        assertEquals("error", rec.get("replyKind"));
        assertEquals(AiChatStreamService.ERR_TOO_MANY_TOOL_ROUNDS, rec.get("errorCode"));
    }

    @Test
    void eval_clientAbort_replyKindInterrupted_withCause() {
        StubChatModel model = new StubChatModel(ChatResponse.builder().content("不该发出的回答").build());
        AiChatStreamService svc = service(model, new MemoryStore(), new MemoryFlight(false), null,
                allow(), new StubDedup(null), noScenario());
        RecordingStream s = new RecordingStream();
        s.cancelled = true;

        svc.run(pf(req("hi")), s);

        Map<String, Object> rec = onlyRecord();
        assertEquals("interrupted", rec.get("replyKind"));
        assertEquals("client_abort", rec.get("interruptCause"));
        assertEquals("", rec.get("replyId"));
    }

    @Test
    void eval_humanTakeover_replyKindInterrupted_withDistinctCause() {
        // 与上条同为 interrupted，但口径相反：这条是"人工态绝不混入 AI 答复"生效的证据，
        // 不是买家放弃。不区分原因就只能看到一堆无法解释的 interrupted（§2 未列、实施补充键）。
        StubChatModel model = new StubChatModel(ChatResponse.builder().content("不该发出的回答").build());
        StubHumanRouter router = new StubHumanRouter();
        router.takeover = true;
        AiChatStreamService svc = service(model, new MemoryStore(), new MemoryFlight(false), List.of(),
                allow(), new StubDedup(null), noScenario(), fixedFaq(DEFAULT_FAQ_TEXT),
                new RecordingIssue(), router);

        svc.run(pf(req("hi")), new RecordingStream());

        Map<String, Object> rec = onlyRecord();
        assertEquals("interrupted", rec.get("replyKind"));
        assertEquals("human_takeover", rec.get("interruptCause"));
    }

    @Test
    void eval_replayAndRouted_leaveNoAiRoundRecord() {
        // 回放没生成、分流轮不调 LLM —— 两条都不是 AI 轮，都不该出现在 AI 轮日志里
        // （分流轮的去向由 C4 人工通道的事件留痕负责，见 C5 §5 埋点表）。
        AiChatStreamService svc = service(new StubChatModel(), new MemoryStore(), new MemoryFlight(false),
                List.of(), allow(), new StubDedup(null), noScenario(), fixedFaq(DEFAULT_FAQ_TEXT),
                new RecordingIssue(), new StubHumanRouter());

        svc.run(new AiChatStreamService.Preflight(USER, req("hi"), true, "7"), new RecordingStream());
        svc.run(new AiChatStreamService.Preflight(USER, req("hi"), false, null,
                new ChatHumanRouter.Routed(9L, "human_active", "已转人工")), new RecordingStream());

        assertTrue(evalSink.lines.isEmpty(), "非 AI 轮不得产生 AI 轮日志");
    }

    @Test
    void eval_twoRounds_twoRecords_withDistinctRoundIds() {
        MemoryStore store = new MemoryStore();
        StubChatModel model = new StubChatModel(ChatResponse.builder().content("好的。").build());
        AiChatStreamService svc = service(model, store, new MemoryFlight(false), null,
                allow(), new StubDedup(null), noScenario());

        svc.run(pf(req("第一问")), new RecordingStream());
        svc.run(pf(req("第二问")), new RecordingStream());

        assertEquals(2, evalSink.lines.size());
        Map<String, Object> r0 = evalSink.parsed(0, OM);
        Map<String, Object> r1 = evalSink.parsed(1, OM);
        assertNotEquals(r0.get("roundId"), r1.get("roundId"),
                "roundId 是 §4.2 标注回填的 join 键，必须逐轮唯一");
        assertEquals("1", r0.get("replyId"));
        assertEquals("2", r1.get("replyId"));
    }

    @Test
    void eval_disabled_writesNothing_roundBehaviourByteIdentical() {
        // §6.5 验收：关闭日志开关后行为不变。这里同时钉住两件事——一条不写、事件协议不变。
        AiProperties props = new AiProperties();
        props.getEval().setEnabled(false);
        MemoryStore store = new MemoryStore();
        MemoryFlight flight = new MemoryFlight(false);
        StubChatModel model = new StubChatModel(ChatResponse.builder().content("你好！").build());
        AiChatStreamService svc = service(model, store, flight, List.of(), allow(),
                new StubDedup(null), noScenario(), fixedFaq(DEFAULT_FAQ_TEXT), new RecordingIssue(),
                new StubHumanRouter(), new InMemoryUnresolvedSignalCounter(), props);
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("你好")), s);

        assertEquals(List.of("delta", "done"), s.order, "关开关后事件序列一字不差");
        assertEquals(1, store.of(USER).size(), "写回照常");
        assertEquals(1, flight.released, "单飞行照常释放");
        assertTrue(evalSink.lines.isEmpty(), "关掉留痕 → 一条都不写");
    }

    @Test
    void eval_writeFailure_doesNotAffectRound() {
        // §1 硬规则：写日志失败仅记日志，不影响主流程。磁盘满不能让一轮对话挂掉。
        evalSink.explode = true;
        MemoryStore store = new MemoryStore();
        MemoryFlight flight = new MemoryFlight(false);
        StubChatModel model = new StubChatModel(ChatResponse.builder().content("你好！").build());
        AiChatStreamService svc = service(model, store, flight, null, allow(),
                new StubDedup(null), noScenario());
        RecordingStream s = new RecordingStream();

        svc.run(pf(req("你好")), s);

        assertEquals(List.of("delta", "done"), s.order, "留痕炸了，对话照常走完");
        assertTrue(s.completed);
        assertEquals(1, store.of(USER).size());
        assertEquals(1, flight.released, "finally 里的释放不受留痕失败影响");
    }

    /** 失败工具 stub：验证留痕记的是未折叠的真实原因 */
    static class FailTool implements ChatTool {
        @Override
        public String name() {
            return "boom";
        }

        @Override
        public String description() {
            return "总是失败（测试）";
        }

        @Override
        public JsonNode parameters() {
            return null;
        }

        @Override
        public ToolResult execute(Long userId, JsonNode arguments) {
            return ToolResult.builder().ok(false).error("TOOL_DOWN").build();
        }
    }

    private int countToolBegin(List<String> order) {
        return (int) order.stream().filter(x -> x.startsWith("tool_begin:")).count();
    }
}
