package com.dsmarket.modules.ai.eval;

import com.dsmarket.modules.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C5 留痕门面单测（REQ-20260908-C5 §2 字段口径 + §1"不得改变既有行为"）。
 *
 * <p>这里不测"指标算得对不对"—— 那是 §3/§4 离线重放脚本的事。本类只钉两件事：
 * <b>记出来的字段是否符合 §2 的字面口径</b>，以及<b>留痕自身失败时主流程是否安全</b>。</p>
 */
class AiEvalRecorderTest {

    private static final long USER = 100L;
    private static final ObjectMapper OM = new ObjectMapper();

    /** 捕获型 sink（能数"写了几条"，也能装成坏盘） */
    static class Capture implements AiEvalSink {
        final List<String> lines = new ArrayList<>();
        boolean explode;

        @Override
        public void write(String jsonLine) {
            if (explode) {
                throw new IllegalStateException("模拟磁盘写满");
            }
            lines.add(jsonLine);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed(int n) {
            try {
                return OM.readValue(lines.get(n), Map.class);
            } catch (Exception e) {
                throw new AssertionError("留痕不是合法 JSON: " + lines.get(n), e);
            }
        }
    }

    private final Capture sink = new Capture();

    private AiEvalRecorder recorder(AiProperties props) {
        return new AiEvalRecorder(props, sink);
    }

    private AiEvalRecorder recorder() {
        return recorder(new AiProperties());
    }

    // ------------------------------------------------------------ 字段口径（§2）

    @Test
    void startRound_hashesUserId_andNeverRetainsPlaintext() {
        AiEvalRecorder rec = recorder();

        RoundTrace t = rec.startRound(USER, "  问句带空白  ");

        assertEquals(UserHash.of(USER, new AiProperties().getEval().getHashSalt()), t.getUserIdHash());
        assertEquals(16, t.getUserIdHash().length());
        assertEquals("问句带空白", t.getContent(), "content 先 trim（§2 记的是问句本身，不含首尾空白）");
        assertFalse(t.getUserIdHash().contains(String.valueOf(USER)),
                "§2 明令不用明文 userId —— 累加器里也不该能找到 100");
    }

    @Test
    void finishRound_recordHasRequiredKeys_andNullishLedgers() {
        AiEvalRecorder rec = recorder();
        RoundTrace t = rec.startRound(USER, "你好");
        t.replyKind(RoundTrace.ReplyKind.AI);
        t.replyId("3");
        t.noteEvent("d");
        t.noteEvent("done");

        rec.finishRound(t);

        assertEquals(1, sink.lines.size());
        Map<String, Object> r = sink.parsed(0);
        assertEquals("ai_round", r.get("kind"));
        assertEquals("3", r.get("replyId"));
        assertEquals("ai", r.get("replyKind"));
        assertEquals(false, r.get("suggestSent"));
        assertEquals("d,done", r.get("eventSeq"));
        assertTrue(r.get("retrieval") instanceof Map, "§2 把 retrieval 的类型定成 object，不是 null");
        assertEquals("none", ((Map<?, ?>) r.get("retrieval")).get("kind"));
        assertNull(((Map<?, ?>) r.get("retrieval")).get("conf"),
                "conf=null ⇒ 本轮压根没调检索工具（与「调了但没召回」的 conf=low 区分开）");
        assertEquals(Map.of(), r.get("outcome"), "§2 outcome 线上恒为空对象，等离线标注 join");
        assertEquals(-1L, ((Number) r.get("firstDeltaMs")).longValue(), "无内容事件 → -1（不用 0）");
        assertTrue(!r.containsKey("suggestReason"), "没发 suggest 就不写该键");
        assertTrue(!r.containsKey("interruptCause"), "没被打断就不写该键");
        assertNotEquals("", r.get("roundId"), "roundId 恒在 —— 它是标注回填的 join 键");
    }

    @Test
    void nullValuedKeys_areStillSerialized_immuneToGlobalJacksonConfig() {
        // 回归守卫（真机 c5-s1-sanity 第一轮 B4 报出的缺陷）：本项目
        // spring.jackson.default-property-inclusion=non_null，若留痕复用容器里的 ObjectMapper，
        // "本轮没发生检索" 的 retrieval 键会被整个删掉 —— §2 必含键缺一个，§4.4 直接判该轮异常，
        // 而且"没检索"与"日志没记下来"再也分不出来。
        //
        // 单测之所以最初没抓到：注入的是默认 new ObjectMapper()（包含 null），与线上 bean 配置不同。
        // 所以这条断言不比对解析结果，而是直接比对**序列化出来的文本**——只有文本里真的出现
        // "retrieval":null，才说明留痕不再受全局 inclusion 策略摆布。
        AiEvalRecorder rec = recorder();
        RoundTrace t = rec.startRound(USER, "q");
        t.replyKind(RoundTrace.ReplyKind.AI);
        rec.finishRound(t);

        String line = sink.lines.get(0);
        assertTrue(line.contains("\"topScore\":null") && line.contains("\"conf\":null"),
                "值为 null 的键在文本里被删掉了（多半是又用回了容器里的 ObjectMapper）：" + line);
        assertTrue(line.contains("\"kind\":\"none\""), "没检索也要写成对象：" + line);
    }

    @Test
    void answerKey_isAlwaysWritten_evenWhenEmpty() {
        // §2 未列补充键 answer。这里钉的是"键必须在"，不是"值对不对"：
        // 纯 error / 干净作废的轮没有答复，而**缺键与空答复在离线侧是两件事** ——
        // 缺键会被 §4.4 判成日志异常，空答复才是"这轮没答"。故无内容事件时写 ""，不写 null、更不省键。
        AiEvalRecorder rec = recorder();
        RoundTrace t = rec.startRound(USER, "q");
        t.replyKind(RoundTrace.ReplyKind.ERROR);
        rec.finishRound(t);

        String line = sink.lines.get(0);
        assertTrue(line.contains("\"answer\":\"\""),
                "没有答复的轮必须写成空串键，缺键会被 §4.4 误判为日志异常：" + line);
    }

    @Test
    void truncatedAnswer_carriesVisibleMarker_inSerializedText() {
        // 截断必须**在文本里看得出来**。只断言对象上的 overflow 计数不够：
        // 标注者拿到的是一行 JSON（或由它导出的 CSV），不是 RoundTrace 对象。
        // 看不见截断，他就会把"日志截断了"读成"模型答了一半"，凭空压低答对率。
        AiEvalRecorder rec = recorder();
        // 默认 answer-max=2000，正常答复根本够不到；把上限压到 4 才构造得出超限这条路径
        RoundTrace cut = new RoundTrace("h", "q", 0L, 40, 4);
        cut.replyKind(RoundTrace.ReplyKind.AI);
        cut.appendAnswer("一二三四五六七八九十");
        rec.finishRound(cut);

        String line = sink.lines.get(0);
        assertTrue(line.contains("\"answer\":\"一二三四…[留痕截断,另有 6 字符未记录]\""),
                "截断标记没写进文本，标注者无从知道这轮被截过：" + line);
    }

    @Test
    void replyKind_defaultsToInterrupted_whenNeverSet() {
        // 默认值必须是 interrupted 而不是 ai：没显式设过 replyKind 的出口只剩"被打断"这一类，
        // 默认成 ai 会把一条被作废的轮算进答对率的分母。
        AiEvalRecorder rec = recorder();
        RoundTrace t = rec.startRound(USER, "hi");

        rec.finishRound(t);

        assertEquals("interrupted", sink.parsed(0).get("replyKind"));
    }

    @Test
    void intent_derivedFromFirstExecutedTool() {
        AiEvalRecorder rec = recorder();

        RoundTrace order = rec.startRound(USER, "查订单");
        order.tool("query_my_order", true, null);
        rec.finishRound(order);
        assertEquals("ORDER_QUERY", sink.parsed(0).get("intent"));

        RoundTrace kb = rec.startRound(USER, "退货政策");
        kb.tool("search_knowledge", true, null);
        kb.tool("query_my_order", true, null);
        rec.finishRound(kb);
        assertEquals("KNOWLEDGE_QA", sink.parsed(1).get("intent"),
                "首个执行的工具决定意图（不引优先级表，口径要能从日志本身复算）");

        RoundTrace unknown = rec.startRound(USER, "别的");
        unknown.tool("brand_new_tool", true, null);
        rec.finishRound(unknown);
        assertEquals("OTHER", sink.parsed(2).get("intent"), "未知工具不静默归错类");

        RoundTrace none = rec.startRound(USER, "闲聊");
        rec.finishRound(none);
        assertEquals("DIRECT_ANSWER", sink.parsed(3).get("intent"));
    }

    @Test
    void tools_reasonOnlyWrittenWhenPresent() {
        AiEvalRecorder rec = recorder();
        RoundTrace t = rec.startRound(USER, "q");
        t.tool("search_knowledge", false, "LOW_CONF");
        t.tool("query_my_order", true, null);

        rec.finishRound(t);

        List<?> tools = (List<?>) sink.parsed(0).get("tools");
        assertEquals(2, tools.size());
        assertTrue(tools.get(0).toString().contains("LOW_CONF"));
        assertFalse(tools.get(1).toString().contains("reason"), "ok=true 不写 reason（§2 的 reason? 可选）");
    }

    @Test
    void eventSeq_overflowIsFoldedIntoCount() {
        AiProperties props = new AiProperties();
        props.getEval().setEventSeqMax(3);
        AiEvalRecorder rec = recorder(props);
        RoundTrace t = rec.startRound(USER, "q");
        t.noteEvent("tool_begin");
        t.noteEvent("d");
        t.noteEvent("d");
        t.noteEvent("d");
        t.noteEvent("done");

        rec.finishRound(t);

        assertEquals("tool_begin,d,d,…+2", sink.parsed(0).get("eventSeq"),
                "超出上限记 …+N（模型答复长度无上限，不封顶单行日志会膨胀）");
    }

    @Test
    void content_truncatedToConfiguredMax() {
        AiProperties props = new AiProperties();
        props.getEval().setContentMax(5);
        AiEvalRecorder rec = recorder(props);

        RoundTrace t = rec.startRound(USER, "一二三四五六七八九十");

        assertEquals("一二三四五", t.getContent(), "§2 content「≤500」：超长截断只影响留痕");
    }

    @Test
    void interrupt_setsKindAndCauseTogether() {
        AiEvalRecorder rec = recorder();
        RoundTrace t = rec.startRound(USER, "q");
        t.interrupt(RoundTrace.INTERRUPT_HUMAN_TAKEOVER);

        rec.finishRound(t);

        Map<String, Object> r = sink.parsed(0);
        assertEquals("interrupted", r.get("replyKind"));
        assertEquals("human_takeover", r.get("interruptCause"),
                "两种 interrupted 口径相反（买家放弃 vs 人工态不混入 AI），必须能分辨");
    }

    // ------------------------------------------------------------ 安全边界（§1/§6.5）

    @Test
    void finishRound_singleEmission_secondCallIsDropped() {
        AiEvalRecorder rec = recorder();
        RoundTrace t = rec.startRound(USER, "q");

        rec.finishRound(t);
        rec.finishRound(t);

        assertEquals(1, sink.lines.size(), "一轮恰好一条：重复收尾被静默丢弃，而不是抛异常炸主流程");
    }

    @Test
    void finishRound_null_isNoop() {
        AiEvalRecorder rec = recorder();

        rec.finishRound(null);

        assertTrue(sink.lines.isEmpty());
    }

    @Test
    void disabled_writesNothing_butStillReturnsUsableTrace() {
        AiProperties props = new AiProperties();
        props.getEval().setEnabled(false);
        AiEvalRecorder rec = recorder(props);

        assertFalse(rec.enabled());
        RoundTrace t = rec.startRound(USER, "q");
        t.replyKind(RoundTrace.ReplyKind.AI);
        rec.finishRound(t);

        assertTrue(sink.lines.isEmpty(), "§6.5 回归对照：关掉开关 → 一条都不写");
        assertEquals(16, t.getUserIdHash().length(), "但累加器照常可用（关的是落盘，不是埋点本身）");
    }

    @Test
    void sinkThrows_isSwallowed() {
        sink.explode = true;
        AiEvalRecorder rec = recorder();
        RoundTrace t = rec.startRound(USER, "q");

        rec.finishRound(t); // 不该抛

        assertTrue(sink.lines.isEmpty());
    }

    @Test
    void sinkThrows_doesNotConsumeTheSingleEmissionGuard() {
        // 细节：写失败时 markEmitted 已经把这一次用掉了。这是有意的——留痕不该重试，
        // 重试只会让一条失败变成两条日志（其中一条是真的），把脏数据混进论文口径。
        sink.explode = true;
        AiEvalRecorder rec = recorder();
        RoundTrace t = rec.startRound(USER, "q");
        rec.finishRound(t);

        sink.explode = false;
        rec.finishRound(t);

        assertTrue(sink.lines.isEmpty(), "失败不重试：宁可少一条，不可多一条");
    }

    @Test
    void distinctRounds_getDistinctRoundIds() {
        AiEvalRecorder rec = recorder();

        RoundTrace a = rec.startRound(USER, "q1");
        RoundTrace b = rec.startRound(USER, "q2");

        assertNotEquals(a.getId(), b.getId());
    }

    // ------------------------------------------------------------ 切片 2：event() 出口

    @Test
    void event_writesEnvelopePlusFields_inThatOrder() {
        recorder().event(AiEvalRecorder.KIND_FEEDBACK, AiEvalRecorder.ACTOR_BUYER, USER,
                new java.util.LinkedHashMap<>(Map.of("satisfied", false)));

        Map<String, Object> m = sink.parsed(0);
        assertEquals(List.of("kind", "ts", "userIdHash", "actor", "satisfied"),
                new ArrayList<>(m.keySet()), "信封在前、业务字段在后：人工看文件与 diff 都省事");
        assertEquals(AiEvalRecorder.KIND_FEEDBACK, m.get("kind"));
        assertEquals(AiEvalRecorder.ACTOR_BUYER, m.get("actor"));
        assertEquals(UserHash.of(USER, new AiProperties().getEval().getHashSalt()), m.get("userIdHash"));
    }

    @Test
    void event_businessFieldsCannotOverrideTheEnvelope() {
        // 用 putIfAbsent 而非 putAll：某处手滑传个 kind/ts 就能把整行归错类，
        // 而重放脚本会照着错类去算 —— 静默偏差。
        Map<String, Object> evil = new java.util.LinkedHashMap<>();
        evil.put("kind", "ai_round");
        evil.put("ts", 1L);
        evil.put("userIdHash", "deadbeefdeadbeef");
        evil.put("actor", "agent");
        evil.put("extra", "ok");

        recorder().event(AiEvalRecorder.KIND_MESSAGE, AiEvalRecorder.ACTOR_BUYER, USER, evil);

        Map<String, Object> m = sink.parsed(0);
        assertEquals(AiEvalRecorder.KIND_MESSAGE, m.get("kind"));
        assertEquals(AiEvalRecorder.ACTOR_BUYER, m.get("actor"));
        assertEquals(UserHash.of(USER, new AiProperties().getEval().getHashSalt()), m.get("userIdHash"));
        assertNotEquals(1L, m.get("ts"), "ts 不得被业务字段顶掉");
        assertEquals("ok", m.get("extra"), "非保留键照常写入");
    }

    @Test
    void event_nullKindOrNullFields_isSafe() {
        AiEvalRecorder rec = recorder();

        rec.event(null, AiEvalRecorder.ACTOR_BUYER, USER, Map.of("a", 1));
        rec.event(AiEvalRecorder.KIND_SEAT, AiEvalRecorder.ACTOR_AGENT, USER, null);

        assertEquals(1, sink.lines.size(), "null kind 是 no-op；null 字段表照常出行");
    }

    @Test
    void event_disabled_writesNothing() {
        AiProperties props = new AiProperties();
        props.getEval().setEnabled(false);

        recorder(props).event(AiEvalRecorder.KIND_SEAT, AiEvalRecorder.ACTOR_AGENT, USER, Map.of());

        assertTrue(sink.lines.isEmpty());
    }

    @Test
    void event_sinkThrows_isSwallowed() {
        sink.explode = true;

        recorder().event(AiEvalRecorder.KIND_MESSAGE, AiEvalRecorder.ACTOR_BUYER, USER, Map.of()); // 不该抛
    }

    @Test
    void event_nullValuesSurvive_becauseTheMapperIsPrivate() {
        // 与切片 1 的 D7 同一条：本项目全局配了 non_null，若复用容器 mapper，
        // 人工/反馈事件里的 null 字段会整个消失，离线侧分不出"没值"与"没记"。
        Map<String, Object> f = new java.util.LinkedHashMap<>();
        f.put("reason", null);
        f.put("collected", true);

        recorder().event(AiEvalRecorder.KIND_FEEDBACK, AiEvalRecorder.ACTOR_BUYER, USER, f);

        assertTrue(sink.lines.get(0).contains("\"reason\":null"), sink.lines.get(0));
    }
}
