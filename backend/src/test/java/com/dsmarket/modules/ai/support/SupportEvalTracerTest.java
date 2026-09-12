package com.dsmarket.modules.ai.support;

import com.dsmarket.modules.ai.config.AiProperties;
import com.dsmarket.modules.ai.enums.AiSupportSender;
import com.dsmarket.modules.ai.enums.AiSupportSessionStatus;
import com.dsmarket.modules.ai.eval.AiEvalRecorder;
import com.dsmarket.modules.ai.eval.AiEvalSink;
import com.dsmarket.modules.ai.eval.UserHash;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SupportEvalTracer 单测（REQ-20260908-C5 §2 后半）。
 *
 * <p>这一层是<b>字段名各写一次</b>的地方（不然六七个调用点各 put 一遍，漏改一处就"某天算不出某个指标"），
 * 所以断言的重点不是"事件有没有发出去"，而是<b>字段名、取值口径、主体身份</b>这三样。</p>
 */
class SupportEvalTracerTest {

    private static final long BUYER = 300L;
    private static final long AGENT = 900L;

    static class Capture implements AiEvalSink {
        static final ObjectMapper OM = new ObjectMapper();

        final List<String> lines = new ArrayList<>();
        boolean explode;
        int writes;

        @Override
        public void write(String jsonLine) {
            writes++;
            if (explode) {
                throw new IllegalStateException("磁盘满了");
            }
            lines.add(jsonLine);
        }

        JsonNode only(String kind) {
            List<JsonNode> hit = new ArrayList<>();
            for (String line : lines) {
                try {
                    JsonNode n = OM.readTree(line);
                    if (kind.equals(n.path("kind").asText())) {
                        hit.add(n);
                    }
                } catch (Exception e) {
                    throw new AssertionError("留痕行不是合法 JSON: " + line, e);
                }
            }
            assertEquals(1, hit.size(), "期望恰好一条 " + kind + "，实得：" + lines);
            return hit.get(0);
        }
    }

    private Capture sink;
    private AiProperties properties;
    private boolean seatOnline;
    private boolean seatThrows;
    private SupportEvalTracer tracer;

    @BeforeEach
    void setUp() {
        sink = new Capture();
        properties = new AiProperties();
        seatOnline = true;
        seatThrows = false;
        SupportSeatPresence presence = () -> {
            if (seatThrows) {
                throw new IllegalStateException("Redis 挂了");
            }
            return seatOnline;
        };
        tracer = new SupportEvalTracer(new AiEvalRecorder(properties, sink), presence);
    }

    // ------------------------------------------------------------ 信封

    @Test
    void everyEvent_carriesTheSameEnvelope() {
        tracer.feedback(BUYER, "12", true, null, false);

        JsonNode e = sink.only(AiEvalRecorder.KIND_FEEDBACK);
        assertEquals(AiEvalRecorder.KIND_FEEDBACK, e.path("kind").asText());
        assertEquals(AiEvalRecorder.ACTOR_BUYER, e.path("actor").asText());
        assertEquals(UserHash.of(BUYER, properties.getEval().getHashSalt()), e.path("userIdHash").asText());
        assertTrue(e.path("ts").asLong() > 0, "ts 必须是真实时间戳");
        assertFalse(sink.lines.get(0).contains(String.valueOf(BUYER)),
                "整行不得出现明文 userId：" + sink.lines.get(0));
    }

    @Test
    void nullValuedFields_areStillSerialized_notDropped() {
        // 与切片 1 同一条教训的另一面：留痕自带 ObjectMapper（本项目全局配了 non_null），
        // 若哪天有人把它换成容器 bean，这里会先红。
        tracer.feedback(BUYER, "12", true, null, false);

        assertTrue(sink.lines.get(0).contains("\"reason\":null"),
                "值为 null 的字段必须留在行里（null 本身是信息）：" + sink.lines.get(0));
    }

    // ------------------------------------------------------------ feedback

    @Test
    void feedback_recordsPoolingOutcome() {
        tracer.feedback(BUYER, "12", false, "回答不对", true);

        JsonNode e = sink.only(AiEvalRecorder.KIND_FEEDBACK);
        assertEquals("12", e.path("replyId").asText());
        assertFalse(e.path("satisfied").asBoolean());
        assertEquals("回答不对", e.path("reason").asText());
        assertTrue(e.path("collected").asBoolean());
    }

    @Test
    void feedback_missingReplyId_becomesEmptyString_notNull() {
        // 与 AI 轮日志的 replyId 同口径：空串而非 null，重放脚本不必为 null/缺键写两套分支
        tracer.feedback(BUYER, null, true, null, false);

        JsonNode e = sink.only(AiEvalRecorder.KIND_FEEDBACK);
        assertTrue(e.has("replyId"));
        assertEquals("", e.path("replyId").asText());
    }

    // ------------------------------------------------------------ support_request

    @Test
    void supportRequest_recordsSeatPresenceAtWriteTime() {
        seatOnline = false;
        tracer.supportRequest(BUYER, "USER_REQUEST", 19L, AiSupportSessionStatus.MESSAGE_LEFT_VALUE);

        JsonNode e = sink.only(AiEvalRecorder.KIND_SUPPORT_REQUEST);
        assertEquals("USER_REQUEST", e.path("origin").asText());
        assertFalse(e.path("seatOnline").asBoolean());
        assertEquals(19L, e.path("sessionId").asLong());
        assertEquals(AiSupportSessionStatus.MESSAGE_LEFT_VALUE, e.path("status").asText());
    }

    @Test
    void supportRequest_seatPresenceUnreadable_recordsNull_notFalse() {
        // false 断言"确实没人在线"，而那是我们并没有验证过的事实。
        // "没测出来"与"测出来是假"必须分得开 —— 同 §2 retrieval.conf=null 的取舍。
        seatThrows = true;

        tracer.supportRequest(BUYER, "ANCHOR_HIT", 19L, "pending_human");

        JsonNode e = sink.only(AiEvalRecorder.KIND_SUPPORT_REQUEST);
        assertTrue(e.has("seatOnline"), "键必须在");
        assertTrue(e.path("seatOnline").isNull(), "读不到记 null，不冒充 false：" + sink.lines.get(0));
        assertEquals("ANCHOR_HIT", e.path("origin").asText(), "在线态读不到不影响本事件的其余字段");
    }

    // ------------------------------------------------------------ message

    @Test
    void message_buyerAndAgent_differOnlyBySenderAndActor() {
        tracer.userMessage(BUYER, 19L, 501L, AiSupportSessionStatus.MESSAGE_LEFT_VALUE);
        tracer.agentMessage(BUYER, 19L, 502L, AiSupportSessionStatus.MESSAGE_LEFT_VALUE);

        List<JsonNode> msgs = new ArrayList<>();
        for (String line : sink.lines) {
            try {
                msgs.add(new ObjectMapper().readTree(line));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        assertEquals(2, msgs.size());
        assertEquals(AiSupportSender.USER_VALUE, msgs.get(0).path("sender").asText());
        assertEquals(AiEvalRecorder.ACTOR_BUYER, msgs.get(0).path("actor").asText());
        assertEquals(AiSupportSender.AGENT_VALUE, msgs.get(1).path("sender").asText());
        assertEquals(AiEvalRecorder.ACTOR_AGENT, msgs.get(1).path("actor").asText());
        // 坐席回复事件的 userIdHash 仍是**买家**的：留言响应率要按买家维度收敛
        assertEquals(msgs.get(0).path("userIdHash").asText(), msgs.get(1).path("userIdHash").asText());
        assertEquals(501L, msgs.get(0).path("messageId").asLong());
    }

    @Test
    void message_neverCarriesTheBody() {
        tracer.userMessage(BUYER, 19L, 501L, "message_left");

        String line = sink.lines.get(0);
        assertFalse(line.contains("content"), "留痕行不该有 content 键：" + line);
    }

    // ------------------------------------------------------------ session / seat

    @Test
    void sessionTransition_recordsActionAndResultingStatus() {
        tracer.sessionTransition(BUYER, 19L, SupportEvalTracer.SESSION_TAKE,
                AiSupportSessionStatus.HUMAN_ACTIVE_VALUE);

        JsonNode e = sink.only(AiEvalRecorder.KIND_SUPPORT_SESSION);
        assertEquals("take", e.path("action").asText());
        assertEquals(AiSupportSessionStatus.HUMAN_ACTIVE_VALUE, e.path("status").asText());
        assertEquals(UserHash.of(BUYER, properties.getEval().getHashSalt()), e.path("userIdHash").asText());
    }

    @Test
    void seat_hashesTheAgent_andIsDistinguishableFromBuyerEvents() {
        tracer.seat(AGENT, SupportEvalTracer.SEAT_LOGIN);

        JsonNode e = sink.only(AiEvalRecorder.KIND_SEAT);
        assertEquals("login", e.path("action").asText());
        assertEquals(AiEvalRecorder.ACTOR_AGENT, e.path("actor").asText());
        assertEquals(UserHash.of(AGENT, properties.getEval().getHashSalt()), e.path("userIdHash").asText());
        // 没有 actor 这个键，离线侧无法区分"某个 hash 发过言"与"某个 hash 上过线"
        assertFalse(UserHash.of(AGENT, properties.getEval().getHashSalt())
                .equals(UserHash.of(BUYER, properties.getEval().getHashSalt())));
    }

    // ------------------------------------------------------------ 硬规则

    @Test
    void disabled_writesNothing_butStillReturns() {
        properties.getEval().setEnabled(false);

        tracer.feedback(BUYER, "12", false, null, true);
        tracer.seat(AGENT, SupportEvalTracer.SEAT_LOGOUT);

        assertTrue(sink.lines.isEmpty(), "开关关的是落盘（§6.5）");
        assertEquals(0, sink.writes, "连 sink 都不该被调用");
    }

    @Test
    void disabled_supportRequest_doesNotEvenReadSeatPresence() {
        // 关开关时不该为留痕多读一次在线态：§1「不得改变既有行为」也包括不给主链路加无谓往返
        properties.getEval().setEnabled(false);
        seatThrows = true;

        tracer.supportRequest(BUYER, "USER_REQUEST", 19L, "pending_human");

        assertTrue(sink.lines.isEmpty());
    }

    @Test
    void sinkThrows_isSwallowed_soMainFlowSurvives() {
        sink.explode = true;

        // §1：写日志失败仅记日志，不得反噬人工通道
        tracer.feedback(BUYER, "12", false, null, false);
        tracer.seat(AGENT, SupportEvalTracer.SEAT_LOGIN);

        assertEquals(2, sink.writes, "两次都尝试写过（失败被吞）");
    }

    @Test
    void tracerConstructor_carriesNoLlmDependency() {
        // 本类被工作台注入，必须连带接受"不经 LLM"的检查（见 AiSupportAdminServiceImplTest 的依赖集断言）
        for (Class<?> t : SupportEvalTracer.class.getDeclaredConstructors()[0].getParameterTypes()) {
            String name = t.getName();
            assertFalse(name.contains("ChatModel") || name.contains("EmbeddingClient") || name.contains("KnowledgeSearch"),
                    "留痕层不得依赖模型/向量化/检索：" + name);
        }
    }

    @Test
    void seatActions_areTheSameWordsAsTheWorkbenchSeatStatusReasons() {
        // 两边同词（§4.8 seat_status 的 reason）才能互相 join；写死在这里防有人只改一边
        assertEquals(SupportEvents.REASON_LOGIN, SupportEvalTracer.SEAT_LOGIN);
        assertEquals(SupportEvents.REASON_LOGOUT, SupportEvalTracer.SEAT_LOGOUT);
    }

    @Test
    void noEventHasUnknownKind() {
        // 枚举下限：本层写出的 kind 必须都是 §2 认得的那几个（防手滑写出拼错的新 kind，
        // 重放脚本会静默跳过它 —— 又是一个"看起来在记、其实没人算"的偏差）
        List<String> known = List.of(AiEvalRecorder.KIND_FEEDBACK, AiEvalRecorder.KIND_SUPPORT_REQUEST,
                AiEvalRecorder.KIND_SEAT, AiEvalRecorder.KIND_MESSAGE, AiEvalRecorder.KIND_SUPPORT_SESSION);

        tracer.feedback(BUYER, "1", false, null, true);
        tracer.supportRequest(BUYER, "USER_REQUEST", 1L, "pending_human");
        tracer.userMessage(BUYER, 1L, 2L, "pending_human");
        tracer.agentMessage(BUYER, 1L, 3L, "message_left");
        tracer.sessionTransition(BUYER, 1L, SupportEvalTracer.SESSION_CLOSE, "closed");
        tracer.seat(AGENT, SupportEvalTracer.SEAT_LOGIN);

        // 六个出口 → 六行（userMessage/agentMessage 共用 KIND_MESSAGE，故 kind 的**去重**数是 5）
        assertEquals(6, sink.lines.size(), "六个出口各出一行");
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String line : sink.lines) {
            try {
                String kind = new ObjectMapper().readTree(line).path("kind").asText();
                assertTrue(known.contains(kind), "出现了 §2 不认识的事件类型：" + kind);
                seen.add(kind);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        assertEquals(new java.util.LinkedHashSet<>(known), seen, "五个事件类型应全部被覆盖到");
    }

    @Test
    void unknownKindIsRejectedByRecorder() {
        // AiEvalRecorder.event 对 null kind 是 no-op（防御）——这里把边界钉住
        new AiEvalRecorder(properties, sink).event(null, AiEvalRecorder.ACTOR_BUYER, BUYER, null);
        assertEquals(0, sink.writes);
    }

    @Test
    void allNullFields_stillProduceAWellFormedLine() {
        // 留痕是"事实记录"：字段取不到值就记 null，但事件本身仍然成立（键在、行在）。
        // 丢掉整条才是更糟的 —— 那会让"发生了什么"从日志里彻底消失。
        tracer.supportRequest(BUYER, null, null, null);

        JsonNode e = sink.only(AiEvalRecorder.KIND_SUPPORT_REQUEST);
        assertTrue(e.has("origin") && e.has("sessionId") && e.has("status"));
        assertThrows(AssertionError.class, () -> sink.only("no-such-kind"),
                "取不到的事件必须让断言炸掉，而不是静默返回空");
    }
}
