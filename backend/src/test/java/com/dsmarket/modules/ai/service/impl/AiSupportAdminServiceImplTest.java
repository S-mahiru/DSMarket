package com.dsmarket.modules.ai.service.impl;

import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.modules.ai.config.AiProperties;
import com.dsmarket.modules.ai.dto.AdminSupportBoardVO;
import com.dsmarket.modules.ai.dto.AdminSupportMessageVO;
import com.dsmarket.modules.ai.dto.AdminSupportSessionDetailVO;
import com.dsmarket.modules.ai.dto.AdminSupportSessionVO;
import com.dsmarket.modules.ai.dto.SupportUnreadCount;
import com.dsmarket.modules.ai.entity.AiSupportMessage;
import com.dsmarket.modules.ai.entity.AiSupportSession;
import com.dsmarket.modules.ai.enums.AiSupportSender;
import com.dsmarket.modules.ai.enums.AiSupportSessionStatus;
import com.dsmarket.modules.ai.mapper.AiSupportMessageMapper;
import com.dsmarket.modules.ai.mapper.AiSupportSessionMapper;
import com.dsmarket.modules.ai.model.ChatRound;
import com.dsmarket.modules.ai.eval.AiEvalRecorder;
import com.dsmarket.modules.ai.eval.AiEvalSink;
import com.dsmarket.modules.ai.eval.UserHash;
import com.dsmarket.modules.ai.session.AiSessionStore;
import com.dsmarket.modules.ai.support.SeatOnlineRegistry;
import com.dsmarket.modules.ai.support.SupportEvalTracer;
import com.dsmarket.modules.ai.support.SupportEventPublisher;
import com.dsmarket.modules.ai.support.SupportEvents;
import com.dsmarket.modules.ai.support.SupportSeatPresence;
import com.dsmarket.modules.ai.support.SupportUserMasker;
import com.dsmarket.modules.user.entity.User;
import com.dsmarket.modules.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiSupportAdminServiceImpl 单测（C4 切片 2：坐席工作台）。
 *
 * <p>覆盖判定表里工作台侧的部分：H8（并发双接入仅一成功）、H13（新会话 session_new）、
 * H15（状态迁移 session_status）、H24（已读不回推买家）、H25（回复不自动关）、
 * H26（结束 → closed + human_close）、H3/H28（等待超时降级）与 §7 脱敏。</p>
 *
 * <p>纯 JUnit5 + 手写替身：推送用记录型 {@link SupportEventPublisher} —— 只有把"发了什么事件"
 * 记下来断言，才能压住"接入时买家到底有没有收到 human_status"这类协议要求。</p>
 */
class AiSupportAdminServiceImplTest {

    private static final long ADMIN = 900L;
    private static final long USER = 300L;
    private static final long SESSION = 55L;
    private static final String BUSY = AiSupportAdminServiceImpl.BUSY_NOTICE_TEXT;

    // ------------------------------------------------------------ 替身

    /** 记录型事件发布器：分别记三条出口，便于断言"哪条通道收到了什么" */
    static class RecordingPublisher implements SupportEventPublisher {
        record Event(Long target, String type, Map<String, Object> payload) {
        }

        final List<Event> buyer = new ArrayList<>();
        final List<Event> broadcast = new ArrayList<>();
        final List<Event> single = new ArrayList<>();

        @Override
        public void toBuyer(Long userId, String type, Map<String, Object> payload) {
            buyer.add(new Event(userId, type, payload));
        }

        @Override
        public void toAdmins(String type, Map<String, Object> payload) {
            broadcast.add(new Event(null, type, payload));
        }

        @Override
        public void toAdmin(Long adminId, String type, Map<String, Object> payload) {
            single.add(new Event(adminId, type, payload));
        }

        List<String> buyerTypes() {
            return buyer.stream().map(Event::type).toList();
        }

        List<String> broadcastTypes() {
            return broadcast.stream().map(Event::type).toList();
        }
    }

    /**
     * C5 留痕替身：只收原始行，解析放到断言里。
     *
     * <p>断言<b>解析后的字段</b>而不是 JSON 文本：{@code json.dumps}/{@code writeValueAsString}
     * 的分隔符风格会让"看着一样却判不等"的假失败反复出现（C5 切片 1 的真机 harness 就踩过）。</p>
     */
    static class MemoryEvalSink implements AiEvalSink {
        static final ObjectMapper OM = new ObjectMapper();

        final List<String> lines = new ArrayList<>();

        @Override
        public void write(String jsonLine) {
            lines.add(jsonLine);
        }

        List<JsonNode> ofKind(String kind) {
            List<JsonNode> out = new ArrayList<>();
            for (String line : lines) {
                JsonNode node;
                try {
                    node = OM.readTree(line);
                } catch (Exception e) {
                    throw new AssertionError("留痕行不是合法 JSON: " + line, e);
                }
                if (kind.equals(node.path("kind").asText())) {
                    out.add(node);
                }
            }
            return out;
        }
    }

    /** 可切换在线态的坐席替身（同时实现两个接口，与生产实现同构） */
    static class FakeSeat implements SupportSeatPresence, SeatOnlineRegistry {
        boolean online;
        final Set<Long> offlineMarked = new HashSet<>();

        @Override
        public boolean anySeatOnline() {
            return online;
        }

        @Override
        public boolean markOnline(Long adminId) {
            boolean newly = !online;
            online = true;
            return newly;
        }

        @Override
        public void markOffline(Long adminId) {
            online = false;
            offlineMarked.add(adminId);
        }

        @Override
        public boolean isOnline(Long adminId) {
            return online;
        }
    }

    /** 内存 AI 会话轮条目（L 决议回放的数据源） */
    static class MemoryRounds implements AiSessionStore {
        final List<ChatRound> rounds = new ArrayList<>();
        boolean explode;

        @Override
        public List<ChatRound> loadRounds(Long userId) {
            if (explode) {
                throw new RuntimeException("redis down");
            }
            return rounds;
        }

        @Override
        public void appendRound(Long userId, ChatRound round) {
            rounds.add(round);
        }
    }

    private AiSupportSessionMapper sessionMapper;
    private AiSupportMessageMapper messageMapper;
    private UserMapper userMapper;
    private MemoryRounds rounds;
    private RecordingPublisher publisher;
    private FakeSeat seat;
    private AiProperties properties;
    private MemoryEvalSink evalSink;
    private long nextMessageId = 1000L;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(AiSupportSessionMapper.class);
        messageMapper = mock(AiSupportMessageMapper.class);
        userMapper = mock(UserMapper.class);
        rounds = new MemoryRounds();
        publisher = new RecordingPublisher();
        seat = new FakeSeat();
        properties = new AiProperties();
        evalSink = new MemoryEvalSink();

        when(sessionMapper.touchActive(anyLong())).thenReturn(1);
        when(messageMapper.insert(any(AiSupportMessage.class))).thenAnswer(inv -> {
            AiSupportMessage m = inv.getArgument(0);
            m.setId(nextMessageId++);
            m.setCreatedAt(LocalDateTime.now());
            return 1;
        });
        when(userMapper.selectById(anyLong())).thenReturn(user(USER, "aie2e_bot"));
        when(userMapper.selectList(any())).thenReturn(List.of(user(USER, "aie2e_bot")));
        when(messageMapper.selectBySessionAsc(anyLong())).thenReturn(List.of());
    }

    private AiSupportAdminServiceImpl service() {
        return new AiSupportAdminServiceImpl(sessionMapper, messageMapper, rounds,
                new SupportUserMasker(userMapper), publisher, seat, seat, properties, tracer());
    }

    /** C5：真 tracer + 记录型 sink —— 直接断言"工作台的动作产生了哪条留痕" */
    private SupportEvalTracer tracer() {
        return new SupportEvalTracer(new AiEvalRecorder(properties, evalSink), seat);
    }

    // ------------------------------------------------------------ 构造

    private AiSupportSession session(long id, String status) {
        AiSupportSession s = new AiSupportSession();
        s.setId(id);
        s.setUserId(USER);
        s.setStatus(status);
        s.setOrigin("USER_REQUEST");
        s.setRequestedAt(LocalDateTime.now().minusMinutes(1));
        return s;
    }

    private AiSupportMessage message(long id, String sender, String content, boolean read) {
        AiSupportMessage m = new AiSupportMessage();
        m.setId(id);
        m.setSessionId(SESSION);
        m.setSender(sender);
        m.setContent(content);
        m.setReadByAgent(read);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    private User user(long id, String username) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        return u;
    }

    // ------------------------------------------------------------ §4.5 三列表

    @Test
    void board_splitsByStatus_carriesUnreadAndMaskedUser() {
        when(sessionMapper.selectByStatuses(any())).thenReturn(List.of(
                session(1L, AiSupportSessionStatus.PENDING_HUMAN_VALUE),
                session(2L, AiSupportSessionStatus.HUMAN_ACTIVE_VALUE),
                session(3L, AiSupportSessionStatus.MESSAGE_LEFT_VALUE)));

        SupportUnreadCount c = new SupportUnreadCount();
        c.setSessionId(2L);
        c.setCnt(3);
        when(messageMapper.countUnreadBySessions(any())).thenReturn(List.of(c));

        AdminSupportBoardVO board = service().board();

        assertEquals(1, board.getQueued().size());
        assertEquals(1, board.getActive().size());
        assertEquals(1, board.getLeft().size());
        assertEquals(2L, board.getActive().get(0).getSessionId());
        assertEquals(3, board.getActive().get(0).getUnreadCount(), "红点 = 该会话未读买家消息数（§4.5）");
        assertEquals(0, board.getQueued().get(0).getUnreadCount());
        assertEquals("a***t", board.getQueued().get(0).getUserMasked(), "§7：工作台默认只给脱敏用户信息");
    }

    @Test
    void board_noSessions_returnsThreeEmptyLists_notNull() {
        when(sessionMapper.selectByStatuses(any())).thenReturn(List.of());

        AdminSupportBoardVO board = service().board();

        assertNotNull(board.getQueued());
        assertTrue(board.getQueued().isEmpty());
        assertTrue(board.getActive().isEmpty());
        assertTrue(board.getLeft().isEmpty());
        // 空集合不得触发未读查询（空 IN () 是非法 SQL）
        verify(messageMapper, never()).countUnreadBySessions(any());
    }

    // ------------------------------------------------------------ §4.5 详情

    @Test
    void detail_returnsMessagesAndAiRoundsTail() {
        when(sessionMapper.selectById(SESSION)).thenReturn(session(SESSION, AiSupportSessionStatus.HUMAN_ACTIVE_VALUE));
        when(messageMapper.selectBySessionAsc(SESSION)).thenReturn(List.of(
                message(1L, AiSupportSender.USER_VALUE, "催发货", false),
                message(2L, AiSupportSender.AGENT_VALUE, "马上查", true)));
        for (int i = 1; i <= 7; i++) {
            rounds.rounds.add(ChatRound.of("r" + i, "问" + i, "答" + i));
        }

        AdminSupportSessionDetailVO vo = service().detail(SESSION);

        assertEquals(2, vo.getMessages().size());
        assertTrue(vo.getMessages().get(0).getReadByAgent() != null, "工作台侧必须带已读态（红点口径）");
        assertEquals(5, vo.getAiSummary().size(), "L 决议：回放最近 N=5 轮，不是全部");
        assertEquals("问7", vo.getAiSummary().get(4).getUserContent(), "取的是末尾 5 轮而非开头 5 轮");
        assertEquals("a***t", vo.getUserMasked());
    }

    @Test
    void detail_noAiRounds_returnsEmptySummary_notFabricated() {
        when(sessionMapper.selectById(SESSION)).thenReturn(session(SESSION, AiSupportSessionStatus.MESSAGE_LEFT_VALUE));

        AdminSupportSessionDetailVO vo = service().detail(SESSION);

        assertTrue(vo.getAiSummary().isEmpty(), "无记录就返回空，前端显示'无 AI 对话记录'（L 决议）");
    }

    @Test
    void detail_aiStoreBroken_stillReturnsDetail() {
        when(sessionMapper.selectById(SESSION)).thenReturn(session(SESSION, AiSupportSessionStatus.MESSAGE_LEFT_VALUE));
        rounds.explode = true;

        // 回放读不到不该让整个详情页 500 —— 工作台打不开比没有 AI 回放严重得多
        AdminSupportSessionDetailVO vo = service().detail(SESSION);

        assertTrue(vo.getAiSummary().isEmpty());
    }

    @Test
    void detail_missingSession_404() {
        when(sessionMapper.selectById(SESSION)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class, () -> service().detail(SESSION));
        assertEquals(404, e.getCode());
    }

    // ------------------------------------------------------------ §4.2 接入（H8）

    @Test
    void take_success_notifiesBothChannels_andMarksBufferRead() {
        when(sessionMapper.selectById(SESSION)).thenReturn(session(SESSION, AiSupportSessionStatus.HUMAN_ACTIVE_VALUE));
        when(sessionMapper.casPendingToActive(SESSION)).thenReturn(1);
        when(messageMapper.markUserMessagesRead(SESSION)).thenReturn(2);

        service().take(SESSION);

        // §4.2：接入时落 SYSTEM 消息（买家断线也能靠回读看到）
        verify(messageMapper).insert(any(AiSupportMessage.class));
        verify(messageMapper).markUserMessagesRead(SESSION);
        // 买家侧：human_status + 那条 SYSTEM 消息
        assertEquals(List.of(SupportEvents.HUMAN_STATUS, SupportEvents.HUMAN_MESSAGE), publisher.buyerTypes());
        // 工作台侧：先报状态迁移（三列表移动），再报消息追加
        assertEquals(List.of(SupportEvents.SESSION_STATUS, SupportEvents.MESSAGE_NEW), publisher.broadcastTypes());
        assertEquals(USER, publisher.buyer.get(0).target());
    }

    @Test
    void take_concurrentSecondSeat_409_andPushesNothing() {
        when(sessionMapper.selectById(SESSION)).thenReturn(session(SESSION, AiSupportSessionStatus.HUMAN_ACTIVE_VALUE));
        when(sessionMapper.casPendingToActive(SESSION)).thenReturn(0); // 已被别人抢到

        BusinessException e = assertThrows(BusinessException.class, () -> service().take(SESSION));

        assertEquals(409, e.getCode(), "H8：并发双接入，落败方必须拿到 409");
        verify(messageMapper, never()).insert(any(AiSupportMessage.class));
        assertTrue(publisher.buyer.isEmpty() && publisher.broadcast.isEmpty(),
                "接入失败不得发出任何'已接入'的事件 —— 否则买家会收到假通知");
    }

    @Test
    void take_missingSession_404() {
        when(sessionMapper.selectById(SESSION)).thenReturn(null);

        assertEquals(404, assertThrows(BusinessException.class, () -> service().take(SESSION)).getCode());
    }

    // ------------------------------------------------------------ §4.5 已读（H24）

    @Test
    void read_marksClearsAndPushesNothingToBuyer() {
        when(sessionMapper.selectById(SESSION)).thenReturn(session(SESSION, AiSupportSessionStatus.HUMAN_ACTIVE_VALUE));
        when(messageMapper.markUserMessagesRead(SESSION)).thenReturn(4);

        assertEquals(4, service().read(SESSION));

        // §4.5 决议 J：已读回执不回推买家（无 human_read 事件）
        assertTrue(publisher.buyer.isEmpty(), "买家不该知道坐席读没读");
        assertTrue(publisher.broadcast.isEmpty(), "已读也不进 admin 流（单坐席本地处理即可，§4.8 原则④）");
    }

    // ------------------------------------------------------------ §4.4 回复（H25）

    @Test
    void reply_onMessageLeft_keepsStatus_andReachesBuyer() {
        when(sessionMapper.selectById(SESSION)).thenReturn(session(SESSION, AiSupportSessionStatus.MESSAGE_LEFT_VALUE));

        AdminSupportMessageVO vo = service().reply(SESSION, "  已经帮您催了  ");

        assertEquals("已经帮您催了", vo.getContent(), "落库前 trim，不存纯空白与首尾空格");
        assertEquals(AiSupportSender.AGENT_VALUE, vo.getSender());
        assertEquals(List.of(SupportEvents.HUMAN_MESSAGE), publisher.buyerTypes());
        assertEquals(List.of(SupportEvents.MESSAGE_NEW), publisher.broadcastTypes());
        // P2 决议（H25）：回复不迁移状态，绝不能顺手 close 或改回 human_active
        verify(sessionMapper, never()).casClose(anyLong());
        verify(sessionMapper, never()).casPendingToMessageLeft(anyLong());
    }

    @Test
    void reply_onPendingHuman_409_requiresTakeFirst() {
        when(sessionMapper.selectById(SESSION)).thenReturn(session(SESSION, AiSupportSessionStatus.PENDING_HUMAN_VALUE));

        assertEquals(409, assertThrows(BusinessException.class, () -> service().reply(SESSION, "在的")).getCode());
        verify(messageMapper, never()).insert(any(AiSupportMessage.class));
    }

    @Test
    void reply_onClosed_409() {
        when(sessionMapper.selectById(SESSION)).thenReturn(session(SESSION, AiSupportSessionStatus.CLOSED_VALUE));

        assertEquals(409, assertThrows(BusinessException.class, () -> service().reply(SESSION, "在的")).getCode());
    }

    @Test
    void reply_blank_400() {
        assertEquals(400, assertThrows(BusinessException.class, () -> service().reply(SESSION, "   ")).getCode());
    }

    @Test
    void reply_tooLong_400() {
        String tooLong = "长".repeat(properties.getSupport().getAgentContentMax() + 1);

        assertEquals(400, assertThrows(BusinessException.class, () -> service().reply(SESSION, tooLong)).getCode());
    }

    // ------------------------------------------------------------ §4.5 结束（H26）

    @Test
    void close_success_pushesHumanCloseToBuyer() {
        when(sessionMapper.selectById(SESSION)).thenReturn(session(SESSION, AiSupportSessionStatus.MESSAGE_LEFT_VALUE));
        when(sessionMapper.casClose(SESSION)).thenReturn(1);

        service().close(SESSION);

        assertEquals(List.of(SupportEvents.HUMAN_CLOSE), publisher.buyerTypes());
        assertEquals(List.of(SupportEvents.SESSION_STATUS), publisher.broadcastTypes());
        assertEquals(AiSupportSessionStatus.CLOSED_VALUE,
                publisher.broadcast.get(0).payload().get("status"));
    }

    @Test
    void close_alreadyClosed_409() {
        when(sessionMapper.selectById(SESSION)).thenReturn(session(SESSION, AiSupportSessionStatus.CLOSED_VALUE));
        when(sessionMapper.casClose(SESSION)).thenReturn(0);

        assertEquals(409, assertThrows(BusinessException.class, () -> service().close(SESSION)).getCode());
        assertTrue(publisher.buyer.isEmpty(), "重复结束不得再推一次 human_close");
    }

    // ------------------------------------------------------------ §4.2 心跳 / 登出

    @Test
    void heartbeat_firstTime_pushesSeatStatusLogin() {
        assertTrue(service().heartbeat(ADMIN));

        assertEquals(1, publisher.single.size());
        assertEquals(SupportEvents.SEAT_STATUS, publisher.single.get(0).type());
        assertEquals("online", publisher.single.get(0).payload().get("state"));
        assertEquals(SupportEvents.REASON_LOGIN, publisher.single.get(0).payload().get("reason"));
        assertTrue(publisher.broadcast.isEmpty(), "seat_status 只通知本人，不广播（§4.8）");
    }

    @Test
    void heartbeat_repeat_doesNotPushLoginAgain() {
        AiSupportAdminServiceImpl svc = service();
        svc.heartbeat(ADMIN);
        publisher.single.clear();

        assertFalse(svc.heartbeat(ADMIN), "多标签共享在线键：第二个标签心跳不该再报一次 login（§4.2 P5）");
        assertTrue(publisher.single.isEmpty());
    }

    @Test
    void logout_pushesOfflineBeforeClearingKey() {
        service().logout(ADMIN);

        assertEquals(1, publisher.single.size());
        assertEquals("offline", publisher.single.get(0).payload().get("state"));
        assertEquals(SupportEvents.REASON_LOGOUT, publisher.single.get(0).payload().get("reason"));
        assertTrue(seat.offlineMarked.contains(ADMIN));
    }

    // ------------------------------------------------------------ §4.3 等待超时（H3/H28）

    @Test
    void sweep_noSeatOnline_doesNothing_andDoesNotEvenScan() {
        seat.online = false;

        assertEquals(0, service().sweepWaitTimeout());

        // 无人在线时买家在 request 那步已拿到"暂无客服在线"；此时再说"坐席繁忙"就是凭空说有客服
        verify(sessionMapper, never()).selectPendingBefore(any());
        assertTrue(publisher.buyer.isEmpty());
    }

    @Test
    void sweep_seatOnline_notifiesOnce_keepsSessionPending() {
        seat.online = true;
        when(sessionMapper.selectPendingBefore(any())).thenReturn(List.of(
                session(SESSION, AiSupportSessionStatus.PENDING_HUMAN_VALUE)));
        when(messageMapper.countSystemMessage(SESSION, BUSY)).thenReturn(0);

        assertEquals(1, service().sweepWaitTimeout());

        // H28：SYSTEM 消息落库（买家 SSE 断了也能靠回读看到）—— 且不实时改会话状态
        verify(messageMapper).insert(any(AiSupportMessage.class));
        assertEquals(List.of(SupportEvents.HUMAN_MESSAGE), publisher.buyerTypes());
        verify(sessionMapper, never()).casPendingToMessageLeft(anyLong());
        verify(sessionMapper, never()).casPendingToActive(anyLong());
    }

    @Test
    void sweep_alreadyNotified_doesNotRepeat() {
        seat.online = true;
        when(sessionMapper.selectPendingBefore(any())).thenReturn(List.of(
                session(SESSION, AiSupportSessionStatus.PENDING_HUMAN_VALUE)));
        when(messageMapper.countSystemMessage(SESSION, BUSY)).thenReturn(1); // 本会话已提示过

        assertEquals(0, service().sweepWaitTimeout(), "提示只发一次，不随扫描频率刷屏");

        verify(messageMapper, never()).insert(any(AiSupportMessage.class));
        assertTrue(publisher.buyer.isEmpty());
    }

    // ------------------------------------------------------------ 结构断言

    @Test
    void constructor_hasNoLlmDependency() {
        Constructor<?> ctor = AiSupportAdminServiceImpl.class.getDeclaredConstructors()[0];
        Set<Class<?>> types = Arrays.stream(ctor.getParameterTypes()).collect(Collectors.toSet());

        for (Class<?> t : types) {
            String name = t.getName();
            assertFalse(name.contains("ChatModel"), "坐席侧不得依赖模型：" + name);
            assertFalse(name.contains("EmbeddingClient"), "坐席侧不得依赖向量化：" + name);
            assertFalse(name.contains("KnowledgeSearch"), "坐席侧不得依赖检索：" + name);
        }
        // 依赖集固定：新增依赖必须回到这里过一遍"它会不会让工作台经过模型"的检查
        // （C5 切片 2 加入 SupportEvalTracer：它是纯 JSON 组装 + 只读坐席在线键，见下一条断言）
        assertEquals(Set.of(AiSupportSessionMapper.class, AiSupportMessageMapper.class,
                        AiSessionStore.class, SupportUserMasker.class, SupportEventPublisher.class,
                        SupportSeatPresence.class, SeatOnlineRegistry.class, AiProperties.class,
                        SupportEvalTracer.class),
                types, "工作台依赖集变化需同步复核'不经 LLM'的结论");
    }

    /**
     * 依赖集是"白名单"而不是"黑名单"，所以新加入的 {@link SupportEvalTracer} 必须<b>连带</b>接受同一套检查
     * —— 否则只是把"注入 ChatModel"挪到下一层，绕过上面那条断言。
     */
    @Test
    void evalTracer_andItsRecorder_alsoCarryNoLlmDependency() {
        for (Class<?> clazz : List.of(SupportEvalTracer.class, AiEvalRecorder.class)) {
            Set<String> names = Arrays.stream(clazz.getDeclaredConstructors()[0].getParameterTypes())
                    .map(Class::getName).collect(Collectors.toSet());
            for (String name : names) {
                assertFalse(name.contains("ChatModel"), clazz.getSimpleName() + " 不得依赖模型：" + name);
                assertFalse(name.contains("EmbeddingClient"), clazz.getSimpleName() + " 不得依赖向量化：" + name);
                assertFalse(name.contains("KnowledgeSearch"), clazz.getSimpleName() + " 不得依赖检索：" + name);
            }
        }
    }

    // ------------------------------------------------------------ C5 切片 2：人工事件留痕

    @Test
    void take_recordsSupportSessionEvent_withBuyerHash() {
        when(sessionMapper.selectById(SESSION)).thenReturn(session(SESSION, AiSupportSessionStatus.PENDING_HUMAN_VALUE));
        when(sessionMapper.casPendingToActive(SESSION)).thenReturn(1);

        service().take(SESSION);

        List<JsonNode> events = evalSink.ofKind(AiEvalRecorder.KIND_SUPPORT_SESSION);
        assertEquals(1, events.size(), "接入恰好一条留痕");
        JsonNode e = events.get(0);
        assertEquals(SupportEvalTracer.SESSION_TAKE, e.path("action").asText());
        assertEquals(AiSupportSessionStatus.HUMAN_ACTIVE_VALUE, e.path("status").asText());
        assertEquals(AiEvalRecorder.ACTOR_AGENT, e.path("actor").asText());
        assertEquals(SESSION, e.path("sessionId").asLong());
        // 主体必须是**买家**（会话拥有者）而不是坐席：转人工成功率按买家维度收敛
        assertEquals(UserHash.of(USER, salt()), e.path("userIdHash").asText());
        assertTrue(e.path("ts").asLong() > 0);
    }

    @Test
    void close_recordsSupportSessionEvent() {
        when(sessionMapper.selectById(SESSION)).thenReturn(session(SESSION, AiSupportSessionStatus.HUMAN_ACTIVE_VALUE));
        when(sessionMapper.casClose(SESSION)).thenReturn(1);

        service().close(SESSION);

        List<JsonNode> events = evalSink.ofKind(AiEvalRecorder.KIND_SUPPORT_SESSION);
        assertEquals(1, events.size());
        assertEquals(SupportEvalTracer.SESSION_CLOSE, events.get(0).path("action").asText());
        assertEquals(AiSupportSessionStatus.CLOSED_VALUE, events.get(0).path("status").asText());
    }

    @Test
    void takeFailedCas_recordsNothing() {
        when(sessionMapper.selectById(SESSION)).thenReturn(session(SESSION, AiSupportSessionStatus.PENDING_HUMAN_VALUE));
        when(sessionMapper.casPendingToActive(SESSION)).thenReturn(0);

        assertThrows(BusinessException.class, () -> service().take(SESSION));

        // H8 落败方：没接上就没有"接入"这个事实。记了会让转人工成功率虚高。
        assertTrue(evalSink.ofKind(AiEvalRecorder.KIND_SUPPORT_SESSION).isEmpty());
    }

    @Test
    void reply_recordsAgentMessage_withSenderAndUnchangedStatus() {
        when(sessionMapper.selectById(SESSION)).thenReturn(session(SESSION, AiSupportSessionStatus.MESSAGE_LEFT_VALUE));

        service().reply(SESSION, "已为您处理");

        List<JsonNode> events = evalSink.ofKind(AiEvalRecorder.KIND_MESSAGE);
        assertEquals(1, events.size());
        JsonNode e = events.get(0);
        assertEquals(AiSupportSender.AGENT_VALUE, e.path("sender").asText());
        assertEquals(AiEvalRecorder.ACTOR_AGENT, e.path("actor").asText());
        assertEquals(SESSION, e.path("sessionId").asLong());
        // P2 决议：回复不迁移状态 —— 留痕里的写后状态必须还是 message_left
        assertEquals(AiSupportSessionStatus.MESSAGE_LEFT_VALUE, e.path("sessionStatus").asText());
        // 不记正文：只有长度之外的全部字段都在，且整行不含消息文本
        assertFalse(events.get(0).has("content"), "留痕不得复制人工对话正文（PG 才是正文权威）");
        assertFalse(evalSink.lines.get(0).contains("已为您处理"), "正文不得出现在留痕行里");
    }

    @Test
    void heartbeat_recordsSeatLoginOnce_andLogoutRecordsIt() {
        AiSupportAdminServiceImpl svc = service();
        svc.heartbeat(ADMIN);
        svc.heartbeat(ADMIN);

        List<JsonNode> seats = evalSink.ofKind(AiEvalRecorder.KIND_SEAT);
        assertEquals(1, seats.size(), "心跳是续期：只记上线那一次，否则日志被 15s 一次的续期淹没");
        assertEquals(SupportEvalTracer.SEAT_LOGIN, seats.get(0).path("action").asText());
        // seat 事件的 userIdHash 是**坐席**的：拿买家哈希去比会 join 错人
        assertEquals(UserHash.of(ADMIN, salt()), seats.get(0).path("userIdHash").asText());
        assertFalse(UserHash.of(ADMIN, salt()).equals(UserHash.of(USER, salt())));

        svc.logout(ADMIN);
        seats = evalSink.ofKind(AiEvalRecorder.KIND_SEAT);
        assertEquals(2, seats.size());
        assertEquals(SupportEvalTracer.SEAT_LOGOUT, seats.get(1).path("action").asText());
    }

    @Test
    void evalDisabled_workbenchStillWorks_butWritesNothing() {
        properties.getEval().setEnabled(false);

        when(sessionMapper.selectById(SESSION)).thenReturn(session(SESSION, AiSupportSessionStatus.MESSAGE_LEFT_VALUE));
        service().reply(SESSION, "已为您处理");

        assertTrue(evalSink.lines.isEmpty(), "开关关的是落盘（§6.5）");
    }

    private String salt() {
        return properties.getEval().getHashSalt();
    }

    @Test
    void take_noticeText_isNotMistakenForOfflineTip() {
        // 接入提示必须说"已接入"，不能出现"暂无客服在线"之类反向话术（AI 不谎报的镜像要求）
        assertFalse(AiSupportAdminServiceImpl.TAKE_NOTICE_TEXT.contains("暂无"));
        assertTrue(AiSupportAdminServiceImpl.BUSY_NOTICE_TEXT.contains("繁忙"));
        assertEquals("本次人工会话已结束。", AiSupportAdminServiceImpl.CLOSE_TEXT);
    }

    @Test
    void detail_aiSummaryUsesConfiguredRounds() {
        properties.getSupport().setAiSummaryRounds(2);
        when(sessionMapper.selectById(SESSION)).thenReturn(session(SESSION, AiSupportSessionStatus.MESSAGE_LEFT_VALUE));
        for (int i = 1; i <= 4; i++) {
            rounds.rounds.add(ChatRound.of("r" + i, "问" + i, "答" + i));
        }

        AdminSupportSessionDetailVO vo = service().detail(SESSION);

        assertEquals(2, vo.getAiSummary().size());
        assertEquals("问3", vo.getAiSummary().get(0).getUserContent(), "N 是可调参数，取末尾 N 轮");
    }

    @Test
    void board_missingUser_fallsBackToPlaceholder() {
        when(sessionMapper.selectByStatuses(any())).thenReturn(List.of(
                session(1L, AiSupportSessionStatus.PENDING_HUMAN_VALUE)));
        when(userMapper.selectList(any())).thenReturn(List.of());

        AdminSupportSessionVO vo = service().board().getQueued().get(0);

        assertEquals("用户#300", vo.getUserMasked(), "查不到用户也不能让这一行空白");
    }

    @Test
    void reply_missingSession_404() {
        when(sessionMapper.selectById(SESSION)).thenReturn(null);

        assertEquals(404, assertThrows(BusinessException.class, () -> service().reply(SESSION, "在的")).getCode());
        verify(messageMapper, never()).insert(any(AiSupportMessage.class));
    }

    @Test
    void close_missingSession_404() {
        when(sessionMapper.selectById(SESSION)).thenReturn(null);

        assertEquals(404, assertThrows(BusinessException.class, () -> service().close(SESSION)).getCode());
    }

    @Test
    void read_missingSession_404() {
        when(sessionMapper.selectById(SESSION)).thenReturn(null);

        assertEquals(404, assertThrows(BusinessException.class, () -> service().read(SESSION)).getCode());
        verify(messageMapper, never()).markUserMessagesRead(anyLong());
    }

    @Test
    void sweep_multipleWaiting_notifiesEachIndependently() {
        seat.online = true;
        when(sessionMapper.selectPendingBefore(any())).thenReturn(List.of(
                session(1L, AiSupportSessionStatus.PENDING_HUMAN_VALUE),
                session(2L, AiSupportSessionStatus.PENDING_HUMAN_VALUE)));
        when(messageMapper.countSystemMessage(eq(1L), eq(BUSY))).thenReturn(1); // 1 号已提示
        when(messageMapper.countSystemMessage(eq(2L), eq(BUSY))).thenReturn(0);

        assertEquals(1, service().sweepWaitTimeout(), "已提示的跳过，未提示的照常");
        assertEquals(1, publisher.buyer.size());
    }
}
