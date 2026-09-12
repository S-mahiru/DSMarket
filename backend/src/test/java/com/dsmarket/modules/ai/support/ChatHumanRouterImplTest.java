package com.dsmarket.modules.ai.support;

import com.dsmarket.modules.ai.config.AiProperties;
import com.dsmarket.modules.ai.dto.AiSupportSessionVO;
import com.dsmarket.modules.ai.dto.SupportRequestResult;
import com.dsmarket.modules.ai.dto.SupportSendResult;
import com.dsmarket.modules.ai.eval.AiEvalRecorder;
import com.dsmarket.modules.ai.eval.AiEvalSink;
import com.dsmarket.modules.ai.enums.AiSupportOrigin;
import com.dsmarket.modules.ai.eval.UserHash;
import com.dsmarket.modules.ai.service.AiSupportSessionService;
import com.dsmarket.modules.ai.session.InMemoryUnresolvedSignalCounter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatHumanRouterImpl 单测（C4 切片 3，主 REQ §2.1 入口分流硬规则 + §4.1 步骤 6）。
 *
 * <p>覆盖：PH 优先于锚点、未命中放行走 AI、锚点转人工+正文转存两步、P3 截断、提示文案与
 * 实际去向一致（不谎报有人接）。</p>
 */
class ChatHumanRouterImplTest {

    private static final long USER = 500L;

    /** 手写替身（接口）：记录调用顺序与入参，避免 Mockito class-mocking */
    static class StubSupport implements AiSupportSessionService {
        boolean takeover;
        String sendStatus = "pending_human";
        long sendSessionId = 11L;
        /** §4.4 P6 幂等命中：本次未落库 */
        boolean duplicate;
        long anchorSessionId = 12L;
        /** 每次 sendMessage 的 [content, clientMsgId] */
        final List<String[]> sends = new ArrayList<>();
        int anchorRequests;
        /** 调用顺序标记，用于证明"先 request 后 send" */
        final List<String> order = new ArrayList<>();

        @Override
        public SupportRequestResult request(Long userId, String origin) {
            throw new UnsupportedOperationException("router 不该走买家可自报的 request 入口");
        }

        @Override
        public SupportSendResult sendMessage(Long userId, String content, String clientMsgId) {
            order.add("send");
            sends.add(new String[]{content, clientMsgId});
            SupportSendResult r = new SupportSendResult();
            r.setMessageId(1L);
            r.setSessionId(sendSessionId);
            r.setStatus(sendStatus);
            r.setDuplicate(duplicate);
            return r;
        }

        @Override
        public AiSupportSessionVO snapshot(Long userId) {
            throw new UnsupportedOperationException("router 不需要快照");
        }

        @Override
        public SupportRequestResult requestByAnchor(Long userId) {
            order.add("requestByAnchor");
            anchorRequests++;
            SupportRequestResult r = new SupportRequestResult();
            r.setSessionId(anchorSessionId);
            r.setStatus("pending_human");
            r.setTip("正在为您接入人工客服…");
            return r;
        }

        @Override
        public boolean hasHumanTakeover(Long userId) {
            return takeover;
        }
    }

    /** C5 留痕替身（同 AiSupportAdminServiceImplTest.MemoryEvalSink，测试间不共享） */
    static class MemorySink implements AiEvalSink {
        static final ObjectMapper OM = new ObjectMapper();

        final List<String> lines = new ArrayList<>();

        @Override
        public void write(String jsonLine) {
            lines.add(jsonLine);
        }

        List<JsonNode> ofKind(String kind) {
            List<JsonNode> out = new ArrayList<>();
            for (String line : lines) {
                try {
                    JsonNode n = OM.readTree(line);
                    if (kind.equals(n.path("kind").asText())) {
                        out.add(n);
                    }
                } catch (Exception e) {
                    throw new AssertionError("留痕行不是合法 JSON: " + line, e);
                }
            }
            return out;
        }
    }

    private StubSupport support;
    private AiProperties properties;
    private InMemoryUnresolvedSignalCounter counter;
    private MemorySink sink;
    private boolean seatOnline;
    private ChatHumanRouterImpl router;

    @BeforeEach
    void setUp() {
        support = new StubSupport();
        properties = new AiProperties();
        counter = new InMemoryUnresolvedSignalCounter();
        sink = new MemorySink();
        seatOnline = true;
        SupportEvalTracer tracer = new SupportEvalTracer(
                new AiEvalRecorder(properties, sink), () -> seatOnline);
        router = new ChatHumanRouterImpl(support, new AnchorWordMatcher(properties), properties, counter, tracer);
    }

    // ------------------------------------------------------------ 未命中

    @Test
    void route_noTakeoverNoAnchor_returnsNull_soAiProceeds() {
        assertNull(router.route(USER, "我的订单什么时候发货", null), "普通问句必须放行走 AI");
        assertEquals(0, support.sends.size(), "不该写任何人工消息");
        assertEquals(0, support.anchorRequests);
    }

    // ------------------------------------------------------------ ① PH 会话

    @Test
    void route_phSession_buffersMessage_andNoticeMatchesStatus() {
        support.takeover = true;
        support.sendSessionId = 21L;

        support.sendStatus = "pending_human";
        ChatHumanRouter.Routed pending = router.route(USER, "在吗", "cid-1");
        assertEquals(ChatHumanRouterImpl.NOTICE_HUMAN_PENDING, pending.notice());
        assertEquals(21L, pending.sessionId());
        assertEquals("pending_human", pending.status());

        support.sendStatus = "human_active";
        assertEquals(ChatHumanRouterImpl.NOTICE_HUMAN_ACTIVE, router.route(USER, "在吗", null).notice());

        support.sendStatus = "message_left";
        assertEquals(ChatHumanRouterImpl.NOTICE_HUMAN_LEFT, router.route(USER, "补充一下", null).notice());

        assertEquals(0, support.anchorRequests, "PH 命中时不该再走锚点转人工");
    }

    @Test
    void route_phSession_passesClientMsgIdThrough_forDedup() {
        support.takeover = true;

        router.route(USER, "在吗", "cid-9");

        assertEquals(1, support.sends.size());
        assertEquals("在吗", support.sends.get(0)[0]);
        assertEquals("cid-9", support.sends.get(0)[1], "幂等键要透传，重发才拦得住");
    }

    @Test
    void route_phWinsOverAnchor_onlyOneSend() {
        // 顺序即语义：已在人工态时，这句话里带"投诉"不该再触发一次转人工
        support.takeover = true;

        ChatHumanRouter.Routed r = router.route(USER, "我要投诉你们", null);

        assertNotNull(r);
        assertEquals(1, support.sends.size(), "只该作为人工消息落一条");
        assertEquals(0, support.anchorRequests, "不该重复转人工（§4.1 步骤 3 幂等）");
    }

    // ------------------------------------------------------------ ② 锚点命中

    @Test
    void route_anchorHit_requestsThenTransfers_inThisOrder() {
        ChatHumanRouter.Routed r = router.route(USER, "我要找人工客服", null);

        assertNotNull(r, "锚点命中必须分流，不能放去 AI");
        assertEquals(List.of("requestByAnchor", "send"), support.order, "先建会话再转存正文（§4.1 步骤 3→6）");
        assertEquals("我要找人工客服", support.sends.get(0)[0], "原文要一并转交，买家不该重打");
    }

    @Test
    void route_anchorHit_offline_reportsHonestLeaveNotice() {
        // 无人在线 → 转存后会话降到 message_left → 提示必须如实说"留言"，不能谎报"正在接入"
        support.sendStatus = "message_left";

        ChatHumanRouter.Routed r = router.route(USER, "转人工", null);

        assertEquals(ChatHumanRouterImpl.NOTICE_ANCHOR_LEFT, r.notice());
        assertTrue(r.notice().contains("留言"), "诚实降级：说清是留言而不是有人接了");
    }

    @Test
    void route_anchorHit_online_reportsPendingNotice() {
        support.sendStatus = "pending_human";

        ChatHumanRouter.Routed r = router.route(USER, "转人工", null);

        assertEquals(ChatHumanRouterImpl.NOTICE_ANCHOR_PENDING, r.notice());
    }

    @Test
    void route_anchorHit_truncatesToUserContentMax_withEllipsis() {
        // P3：截断至 ≤max 且带省略号。这里调小上限来压边界。
        // 真机可达性：content 命中锚点会豁免 AI 态的 500 上限（C1 §2），只受绝对上限 4000 约束，
        // 所以 content ∈ (2000, 4000] 且命中锚点时就真会截断（见 ChatHumanRouterImpl#anchorContent 注释）。
        // 正文必须自带锚点词——截断只发生在锚点路径上，纯长文本走不到这里。
        properties.getSupport().setUserContentMax(10);
        String long_ = "转人工一二三四五六七八九十"; // 13 字，含锚点「转人工」

        router.route(USER, long_, null);

        String stored = support.sends.get(0)[0];
        assertEquals(10, stored.length(), "截断后总长不得超过上限（超一字 sendMessage 就 400）");
        assertTrue(stored.endsWith("…"), "截断要有省略号标记，不能看起来像完整的");
        assertEquals("转人工一二三四五六…", stored);
    }

    @Test
    void route_anchorHit_contentWithinLimit_isNotTruncated() {
        router.route(USER, "转人工", null);
        assertEquals("转人工", support.sends.get(0)[0], "短正文原样转存，不加省略号");
    }

    @Test
    void route_anchorHit_doesNotLeaveOutTheTriggeringContent() {
        // P3 明写"不转存 → 禁止"（否则长申诉正文丢失，买家需重打）：任何锚点命中都必须带正文
        router.route(USER, "我要投诉，订单 20260910 一直没发货", null);
        assertEquals("我要投诉，订单 20260910 一直没发货", support.sends.get(0)[0]);
    }

    @Test
    void route_f6EmotionWord_transfersDirectly_notJustSuggest() {
        // 2026-09-10 裁决：§4.6 情绪词集与 §5.1 锚点集重叠（"投诉"两边都在、行为相反），
        // 重叠按**直接转人工**处理 —— 不再走"只发 suggest 气泡、点后才转"的原设计。
        // 这条把裁决钉在路由行为上：返回 null 就等于退回气泡语义。
        ChatHumanRouter.Routed r = router.route(USER, "你们这个服务气死我了", null);

        assertNotNull(r, "情绪词必须触发转人工，而不是放行走 AI 去发气泡");
        assertEquals(1, support.anchorRequests, "走内部转人工口（origin=ANCHOR_HIT）");
        assertEquals("你们这个服务气死我了", support.sends.get(0)[0], "触发正文同样要转存，不能只留个标记");
    }

    // ------------------------------------------------------------ F6 触发③：转人工清零

    @Test
    void route_anchorHit_clearsUnresolvedSignals() {
        // DECISION D20/D21：转人工即清零 —— 已经交给人了，不该继续算在 AI 的账上。
        // 不清的后果是具体的：买家转人工又关掉、回到 AI 再问一句，会凭空收到一个
        // suggest(UNRESOLVED)（那是上一段会话攒的），提示他去转一个刚结束的人工。
        counter.preset(USER, 3);

        router.route(USER, "转人工", null);

        assertEquals(0, counter.count(USER), "锚点转人工后计数必须归零");
        assertEquals(1, counter.clearCalls.get());
    }

    @Test
    void route_phSession_clearsUnresolvedSignals() {
        // PH 分支同样要清：买家从别的入口（点建议气泡）转的人工，然后误走 /chat ——
        // 这条路径也要把 AI 侧的账清掉，否则下次回到 AI 会看到残留计数。
        counter.preset(USER, 2);
        support.takeover = true;

        router.route(USER, "在吗", null);

        assertEquals(0, counter.count(USER));
    }

    @Test
    void route_noMatch_doesNotTouchCounter() {
        // 未命中 = 照常走 AI，此时清计数会把买家真正攒下的未解决信号抹掉（少发气泡）
        counter.preset(USER, 2);

        assertNull(router.route(USER, "我的订单什么时候发货", null));

        assertEquals(2, counter.count(USER), "放行走 AI 的分支不得动计数");
        assertEquals(0, counter.clearCalls.get());
    }

    // ------------------------------------------------------------ C5 切片 2：分流轮留痕

    @Test
    void route_anchorHit_recordsSupportRequestAndUserMessage() {
        support.sendStatus = "message_left";

        router.route(USER, "转人工", null);

        List<JsonNode> req = sink.ofKind(AiEvalRecorder.KIND_SUPPORT_REQUEST);
        assertEquals(1, req.size(), "锚点自动转人工也要计入转人工请求数（§3 转人工成功率）");
        assertEquals(AiSupportOrigin.ANCHOR_HIT_VALUE, req.get(0).path("origin").asText(),
                "origin 必须是服务端内部的 ANCHOR_HIT，不是买家可自报的 USER_REQUEST");
        assertEquals(UserHash.of(USER, properties.getEval().getHashSalt()), req.get(0).path("userIdHash").asText());
        // status 记的是 requestByAnchor 的直接结果（stub 恒 pending_human），
        // 而"这条正文落库后会话降到 message_left"由 message 事件的 sessionStatus 表达 —— 两个事实分开记
        assertEquals("pending_human", req.get(0).path("status").asText());

        List<JsonNode> msg = sink.ofKind(AiEvalRecorder.KIND_MESSAGE);
        assertEquals(1, msg.size());
        assertEquals("USER", msg.get(0).path("sender").asText());
        assertEquals(AiEvalRecorder.ACTOR_BUYER, msg.get(0).path("actor").asText());
        assertEquals("message_left", msg.get(0).path("sessionStatus").asText());
        assertFalse(sink.lines.get(sink.lines.size() - 1).contains("转人工"),
                "留痕不得复制人工对话正文（PG 才是正文权威）");
    }

    @Test
    void route_phSession_recordsUserMessageOnly_notAnotherRequest() {
        support.takeover = true;
        support.sendSessionId = 21L;

        router.route(USER, "在吗", null);

        assertTrue(sink.ofKind(AiEvalRecorder.KIND_SUPPORT_REQUEST).isEmpty(),
                "已在人工态时只是人工通道的一条消息，不是新的转人工请求");
        assertEquals(1, sink.ofKind(AiEvalRecorder.KIND_MESSAGE).size());
    }

    @Test
    void route_duplicateSend_recordsNoMessage() {
        // §4.4 P6 幂等命中：这一条没落库。记了会让留言数随重试虚增 —— 而它是"留言响应率"的分母。
        support.takeover = true;
        support.duplicate = true;

        router.route(USER, "在吗", "cid-1");

        assertTrue(sink.ofKind(AiEvalRecorder.KIND_MESSAGE).isEmpty());
    }

    @Test
    void route_noMatch_recordsNothing() {
        assertNull(router.route(USER, "我的订单什么时候发货", null));
        assertTrue(sink.lines.isEmpty(), "放行走 AI 就不是人工事件，一条都不该写");
    }

    @Test
    void evalDisabled_recordsNothing_butRoutingUnchanged() {
        properties.getEval().setEnabled(false);
        support.sendStatus = "message_left";

        ChatHumanRouter.Routed r = router.route(USER, "转人工", null);

        assertNotNull(r, "关开关不得改变分流行为（§1 硬规则）");
        assertEquals(ChatHumanRouterImpl.NOTICE_ANCHOR_LEFT, r.notice());
        assertTrue(sink.lines.isEmpty(), "开关关的是落盘（§6.5）");
    }

    // ------------------------------------------------------------ 竞态探针

    @Test
    void hasHumanTakeover_delegatesToService() {
        support.takeover = false;
        assertTrue(!router.hasHumanTakeover(USER));
        support.takeover = true;
        assertTrue(router.hasHumanTakeover(USER));
    }
}
