package com.dsmarket.modules.ai.service.impl;

import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.modules.ai.config.AiProperties;
import com.dsmarket.modules.ai.dto.AiSupportMessageVO;
import com.dsmarket.modules.ai.dto.AiSupportSessionVO;
import com.dsmarket.modules.ai.dto.SupportRequestResult;
import com.dsmarket.modules.ai.dto.SupportSendResult;
import com.dsmarket.modules.ai.entity.AiSupportMessage;
import com.dsmarket.modules.ai.entity.AiSupportSession;
import com.dsmarket.modules.ai.enums.AiSupportOrigin;
import com.dsmarket.modules.ai.enums.AiSupportSender;
import com.dsmarket.modules.ai.enums.AiSupportSessionStatus;
import com.dsmarket.modules.ai.limit.SupportRateLimiter;
import com.dsmarket.modules.ai.mapper.AiSupportMessageMapper;
import com.dsmarket.modules.ai.mapper.AiSupportSessionMapper;
import com.dsmarket.modules.ai.session.SupportDedupStore;
import com.dsmarket.modules.ai.support.SupportEventPublisher;
import com.dsmarket.modules.ai.support.SupportEvents;
import com.dsmarket.modules.ai.support.SupportSeatPresence;
import com.dsmarket.modules.ai.support.SupportUserMasker;
import com.dsmarket.modules.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiSupportSessionServiceImpl 单测（C4 切片 1，买家侧闭环）。
 *
 * <p>覆盖 REQ-20260907-C4 判定表：H2（无坐席→留言降级）、H6/H6b/H6c（PH 三态收消息语义）、
 * H7（重复 request 幂等）、H12（closed 后新建）、H18（closed 后发消息 409）、
 * H20~H23（会话恢复/终态回溯/无会话）。同时含两条<b>结构断言</b>：人工态服务不得依赖任何 LLM 组件、
 * 买家侧 VO 不得外露 read_by_agent。</p>
 *
 * <p>纯 JUnit5 + 手写替身（mock 接口，免 byte-buddy agent）：坐席在线态用可切换 stub，
 * 幂等键用内存 Map —— 避免为"有没有人接"和"是不是重发"引入 Redis。</p>
 */
class AiSupportSessionServiceImplTest {

    private static final long USER = 300L;

    /** 可切换在线态的坐席接缝替身（切片 1 生产用恒 false；单测需要压在线分支） */
    static class ToggleSeatPresence implements SupportSeatPresence {
        private boolean online;

        ToggleSeatPresence(boolean online) {
            this.online = online;
        }

        @Override
        public boolean anySeatOnline() {
            return online;
        }
    }

    /** 记录型事件发布器（切片 2）：人工侧服务只该往 admin 通道发增量，不该碰买家通道 */
    static class RecordingPublisher implements SupportEventPublisher {
        record Event(String type, Map<String, Object> payload) {
        }

        final List<Event> buyer = new ArrayList<>();
        final List<Event> broadcast = new ArrayList<>();

        @Override
        public void toBuyer(Long userId, String type, Map<String, Object> payload) {
            buyer.add(new Event(type, payload));
        }

        @Override
        public void toAdmins(String type, Map<String, Object> payload) {
            broadcast.add(new Event(type, payload));
        }

        @Override
        public void toAdmin(Long adminId, String type, Map<String, Object> payload) {
            broadcast.add(new Event(type, payload));
        }

        List<String> broadcastTypes() {
            return broadcast.stream().map(Event::type).toList();
        }
    }

    /** 内存幂等键替身：key = userId:sessionId:clientMsgId（真实现的 sha256/Redis 由另测覆盖） */
    static class MemoryDedup implements SupportDedupStore {
        private final Map<String, Long> data = new HashMap<>();

        @Override
        public Long findMessageId(Long userId, Long sessionId, String clientMsgId) {
            return data.get(userId + ":" + sessionId + ":" + clientMsgId);
        }

        @Override
        public void mark(Long userId, Long sessionId, String clientMsgId, Long messageId) {
            data.put(userId + ":" + sessionId + ":" + clientMsgId, messageId);
        }
    }

    private AiSupportSessionMapper sessionMapper;
    private AiSupportMessageMapper messageMapper;
    private SupportRateLimiter rateLimiter;
    private MemoryDedup dedup;
    private ToggleSeatPresence presence;
    private RecordingPublisher publisher;
    private AiProperties properties;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(AiSupportSessionMapper.class);
        messageMapper = mock(AiSupportMessageMapper.class);
        rateLimiter = mock(SupportRateLimiter.class);
        dedup = new MemoryDedup();
        presence = new ToggleSeatPresence(false); // 切片 1 缺省语义：无人在线
        publisher = new RecordingPublisher();
        properties = new AiProperties();          // 真配置：support.userContentMax=2000 等
        when(rateLimiter.allow(anyLong())).thenReturn(true);
    }

    private AiSupportSessionServiceImpl service() {
        // 脱敏器用真实现（脱敏规则本身另测）：本类关心的是"发事件时带对了脱敏名"
        return new AiSupportSessionServiceImpl(
                sessionMapper, messageMapper, rateLimiter, dedup, presence, publisher,
                new SupportUserMasker(mock(UserMapper.class)), properties);
    }

    // ------------------------------------------------------------ 通用构造

    private AiSupportSession session(long id, String status) {
        AiSupportSession s = new AiSupportSession();
        s.setId(id);
        s.setUserId(USER);
        s.setStatus(status);
        s.setOrigin(AiSupportOrigin.USER_REQUEST_VALUE);
        s.setRequestedAt(LocalDateTime.now());
        return s;
    }

    /** 让 insert 回填 id（模拟 MyBatis-Plus 主键回写）并返回 1；下一次冲突可选 */
    private void stubSessionInsertAssigningId(long id) {
        when(sessionMapper.insert(any(AiSupportSession.class))).thenAnswer(inv -> {
            AiSupportSession s = inv.getArgument(0);
            s.setId(id);
            return 1;
        });
    }

    private void stubMessageInsertAssigningId(long id) {
        when(messageMapper.insert(any(AiSupportMessage.class))).thenAnswer(inv -> {
            AiSupportMessage m = inv.getArgument(0);
            m.setId(id);
            m.setCreatedAt(LocalDateTime.now()); // 真实现由 MyMetaObjectHandler 回填，事件载荷要用它
            return 1;
        });
    }

    private AiSupportSession capturedSessionInsert() {
        ArgumentCaptor<AiSupportSession> cap = ArgumentCaptor.forClass(AiSupportSession.class);
        verify(sessionMapper).insert(cap.capture());
        return cap.getValue();
    }

    private AiSupportMessage capturedMessageInsert() {
        ArgumentCaptor<AiSupportMessage> cap = ArgumentCaptor.forClass(AiSupportMessage.class);
        verify(messageMapper).insert(cap.capture());
        return cap.getValue();
    }

    // ------------------------------------------------------------ C4-F1 发起转人工

    @Test
    void request_noSessionOffline_insertsPending_andReportsMessageLeft() {
        when(sessionMapper.selectActiveByUser(USER)).thenReturn(null);
        stubSessionInsertAssigningId(11L);

        SupportRequestResult r = service().request(USER, null);

        // DB 行如实是 pending_human（等到首条留言落库才 CAS 降级，见 DECISION-20260908-C4）
        AiSupportSession inserted = capturedSessionInsert();
        assertEquals(AiSupportSessionStatus.PENDING_HUMAN_VALUE, inserted.getStatus());
        assertEquals(AiSupportOrigin.USER_REQUEST_VALUE, inserted.getOrigin(), "origin 缺省补 USER_REQUEST");
        assertNotNull(inserted.getRequestedAt());

        // 对外如实报"无人在线，请留言"（H2）——不得谎报"正在接入"
        assertEquals(AiSupportSessionStatus.MESSAGE_LEFT_VALUE, r.getStatus());
        assertEquals(AiSupportSessionServiceImpl.TIP_OFFLINE, r.getTip());
        assertTrue(r.isPromptLeave(), "无人在线必须引导留言");
        assertEquals(11L, r.getSessionId());
    }

    @Test
    void request_onlineSeat_insertsPending_andReportsPendingTip() {
        when(sessionMapper.selectActiveByUser(USER)).thenReturn(null);
        stubSessionInsertAssigningId(11L);
        presence.online = true;

        SupportRequestResult r = service().request(USER, AiSupportOrigin.USER_REQUEST_VALUE);

        assertEquals(AiSupportSessionStatus.PENDING_HUMAN_VALUE, r.getStatus());
        assertEquals(AiSupportSessionServiceImpl.TIP_PENDING, r.getTip());
        assertFalse(r.isPromptLeave());
    }

    @Test
    void request_existingPending_idempotent_noNewSession() {
        when(sessionMapper.selectActiveByUser(USER)).thenReturn(
                session(7L, AiSupportSessionStatus.PENDING_HUMAN_VALUE));

        SupportRequestResult r = service().request(USER, AiSupportOrigin.AI_SUGGEST_VALUE);

        assertEquals(7L, r.getSessionId(), "H7：复用现有会话 id");
        assertEquals(AiSupportSessionStatus.MESSAGE_LEFT_VALUE, r.getStatus(), "无人在线仍回留言引导");
        assertTrue(r.isPromptLeave());
        verify(sessionMapper, never()).insert(any(AiSupportSession.class));
    }

    @Test
    void request_existingHumanActive_reportsHumanActive_noPromptLeave() {
        when(sessionMapper.selectActiveByUser(USER)).thenReturn(
                session(7L, AiSupportSessionStatus.HUMAN_ACTIVE_VALUE));

        SupportRequestResult r = service().request(USER, null);

        assertEquals(AiSupportSessionStatus.HUMAN_ACTIVE_VALUE, r.getStatus());
        assertEquals(AiSupportSessionServiceImpl.TIP_EXISTING_HUMAN_ACTIVE, r.getTip());
        assertFalse(r.isPromptLeave(), "已在人工处理中，不重复引导留言");
        verify(sessionMapper, never()).insert(any(AiSupportSession.class));
    }

    @Test
    void request_existingMessageLeft_reportsReuseTip() {
        when(sessionMapper.selectActiveByUser(USER)).thenReturn(
                session(7L, AiSupportSessionStatus.MESSAGE_LEFT_VALUE));

        SupportRequestResult r = service().request(USER, null);

        assertEquals(AiSupportSessionStatus.MESSAGE_LEFT_VALUE, r.getStatus());
        assertEquals(AiSupportSessionServiceImpl.TIP_EXISTING_MESSAGE_LEFT, r.getTip());
        assertFalse(r.isPromptLeave(), "已是留言态：复用追加，非首次引导");
    }

    @Test
    void request_onlyClosedSession_createsNewSession() {
        // 活跃查询为空（closed 不在 PH 集合）→ H12 新建
        when(sessionMapper.selectActiveByUser(USER)).thenReturn(null);
        stubSessionInsertAssigningId(12L);

        SupportRequestResult r = service().request(USER, null);

        assertEquals(12L, r.getSessionId());
        verify(sessionMapper).insert(any(AiSupportSession.class));
    }

    @Test
    void request_concurrentDuplicateKey_fallsBackToExistingSession() {
        // 并发：另一请求先插入成功 → 本请求撞部分唯一索引 → 回查活跃会话幂等返回（H7）
        when(sessionMapper.selectActiveByUser(USER))
                .thenReturn(null)
                .thenReturn(session(9L, AiSupportSessionStatus.PENDING_HUMAN_VALUE));
        when(sessionMapper.insert(any(AiSupportSession.class)))
                .thenThrow(new DuplicateKeyException("uk_ai_support_session_active_user"));

        SupportRequestResult r = service().request(USER, null);

        assertEquals(9L, r.getSessionId(), "冲突后应幂等回退到胜出会话，而不是把冲突抛给买家");
    }

    @Test
    void request_concurrentDuplicateKey_andNoActiveFound_rethrows() {
        // 回查也查不到（对方会话同瞬间已 closed）→ 如实上抛，不假装成功
        when(sessionMapper.selectActiveByUser(USER)).thenReturn(null);
        when(sessionMapper.insert(any(AiSupportSession.class)))
                .thenThrow(new DuplicateKeyException("uk_ai_support_session_active_user"));

        assertThrows(DuplicateKeyException.class, () -> service().request(USER, null));
    }

    @Test
    void request_anchorHitOrigin_rejected400() {
        // ANCHOR_HIT 是服务端内部途径，买家不得自报（否则可绕过正文长度限制）
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().request(USER, AiSupportOrigin.ANCHOR_HIT_VALUE));
        assertEquals(400, ex.getCode());
        verify(sessionMapper, never()).insert(any(AiSupportSession.class));
    }

    @Test
    void request_bogusOrigin_rejected400() {
        BusinessException ex = assertThrows(BusinessException.class, () -> service().request(USER, "HUMAN_ACTIVE"));
        assertEquals(400, ex.getCode());
    }

    @Test
    void request_aiSuggestOrigin_accepted() {
        when(sessionMapper.selectActiveByUser(USER)).thenReturn(null);
        stubSessionInsertAssigningId(11L);

        service().request(USER, AiSupportOrigin.AI_SUGGEST_VALUE);

        assertEquals(AiSupportOrigin.AI_SUGGEST_VALUE, capturedSessionInsert().getOrigin());
    }

    // ------------------------------------------------------------ C4 切片 3：锚点入口与竞态探针

    @Test
    void requestByAnchor_createsSession_withAnchorHitOrigin() {
        // 锚点路径与 request 的唯一差别就是 origin：它必须记成 ANCHOR_HIT，否则事后分不清
        // 这次转人工是买家点的按钮还是消息里命中了词（也影响后续误伤率统计，§10 A10）
        when(sessionMapper.selectActiveByUser(USER)).thenReturn(null);
        stubSessionInsertAssigningId(21L);

        SupportRequestResult r = service().requestByAnchor(USER);

        assertEquals(AiSupportOrigin.ANCHOR_HIT_VALUE, capturedSessionInsert().getOrigin());
        assertEquals(21L, r.getSessionId());
    }

    @Test
    void requestByAnchor_existingSession_idempotent_noNewSession() {
        // 与 request 同享幂等（H7）：已有 PH 会话 → 复用，不新建
        when(sessionMapper.selectActiveByUser(USER)).thenReturn(session(31L, "pending_human"));
        presence = new ToggleSeatPresence(true); // 有人在线才不会把 pending 报成 message_left

        SupportRequestResult r = service().requestByAnchor(USER);

        assertEquals(31L, r.getSessionId());
        assertEquals(AiSupportSessionStatus.PENDING_HUMAN_VALUE, r.getStatus());
        verify(sessionMapper, never()).insert(any(AiSupportSession.class));
    }

    @Test
    void hasHumanTakeover_trueForEveryPhStatus() {
        // PH 三态全部算"人工接管中"——/chat 的入口分流与竞态复查都靠它
        for (String status : List.of("pending_human", "human_active", "message_left")) {
            when(sessionMapper.selectActiveByUser(USER)).thenReturn(session(41L, status));
            assertTrue(service().hasHumanTakeover(USER), "PH 应判接管：" + status);
        }
    }

    @Test
    void hasHumanTakeover_falseWhenNoSessionOrClosed() {
        when(sessionMapper.selectActiveByUser(USER)).thenReturn(null);
        assertFalse(service().hasHumanTakeover(USER), "无人工会话 = 纯 AI 态");

        // 防御性重复判定：selectActiveByUser 的 SQL 已限定 PH，但若将来有人放宽该 SQL，
        // 这层判断要能兜住 —— 用 closed 行直接压这个分支
        when(sessionMapper.selectActiveByUser(USER)).thenReturn(session(42L, "closed"));
        assertFalse(service().hasHumanTakeover(USER), "closed 不属于 PH");
    }

    // ------------------------------------------------------------ C4-F4 发消息

    @Test
    void sendMessage_noSession_409NoActiveSession() {
        when(sessionMapper.selectLatestByUser(USER)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().sendMessage(USER, "在吗", null));
        assertEquals(409, ex.getCode());
        verify(messageMapper, never()).insert(any(AiSupportMessage.class));
    }

    @Test
    void sendMessage_closedSession_409_andDoesNotPersist() {
        when(sessionMapper.selectLatestByUser(USER)).thenReturn(
                session(7L, AiSupportSessionStatus.CLOSED_VALUE));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().sendMessage(USER, "还想问", null));

        assertEquals(409, ex.getCode(), "H18：closed 后发消息 → 409");
        assertEquals(AiSupportSessionServiceImpl.SESSION_CLOSED_MESSAGE, ex.getMessage());
        verify(messageMapper, never()).insert(any(AiSupportMessage.class));
        verify(sessionMapper, never()).touchActive(anyLong());
    }

    @Test
    void sendMessage_pendingOffline_persistsAndMigratesToMessageLeft() {
        when(sessionMapper.selectLatestByUser(USER)).thenReturn(
                session(7L, AiSupportSessionStatus.PENDING_HUMAN_VALUE));
        when(sessionMapper.touchActive(7L)).thenReturn(1);
        when(sessionMapper.casPendingToMessageLeft(7L)).thenReturn(1);
        stubMessageInsertAssigningId(55L);

        SupportSendResult r = service().sendMessage(USER, "我的订单还没到", null);

        AiSupportMessage saved = capturedMessageInsert();
        assertEquals(AiSupportSender.USER_VALUE, saved.getSender());
        assertEquals("我的订单还没到", saved.getContent());
        assertEquals(Boolean.FALSE, saved.getReadByAgent(), "买家消息进工作台红点，初始未读");
        verify(sessionMapper).casPendingToMessageLeft(7L);

        assertEquals(55L, r.getMessageId());
        assertEquals(AiSupportSessionStatus.MESSAGE_LEFT_VALUE, r.getStatus(), "写入后如实反映降级态（H2）");
        assertFalse(r.isDuplicate());

        // §4.8：买家这条留言进工作台（红点 + 置顶），会话降级报状态迁移 —— 先消息后状态
        assertEquals(List.of(SupportEvents.MESSAGE_NEW, SupportEvents.SESSION_STATUS),
                publisher.broadcastTypes());
        assertEquals(AiSupportSender.USER_VALUE, publisher.broadcast.get(0).payload().get("sender"));
        assertTrue(publisher.buyer.isEmpty(),
                "买家自己的消息不回推给他：前端已本地渲染，推回去会重复显示");
    }

    @Test
    void sendMessage_pendingOfflineButCasLoses_doesNotClaimDowngrade() {
        // 读到 pending 与 CAS 之间坐席恰好接入 → CAS 0 行。此时如实返回"未降级"，
        // 不能因为"本来打算降级"就对外声称 message_left（对前台谎报状态）
        when(sessionMapper.selectLatestByUser(USER)).thenReturn(
                session(7L, AiSupportSessionStatus.PENDING_HUMAN_VALUE));
        when(sessionMapper.touchActive(7L)).thenReturn(1);
        when(sessionMapper.casPendingToMessageLeft(7L)).thenReturn(0);
        stubMessageInsertAssigningId(55L);

        SupportSendResult r = service().sendMessage(USER, "等接入时先说一句", null);

        assertEquals(AiSupportSessionStatus.PENDING_HUMAN_VALUE, r.getStatus(),
                "CAS 未生效就不能对外声称已降级");
        assertFalse(publisher.broadcastTypes().contains(SupportEvents.SESSION_STATUS),
                "没有真的迁移，就不该推 session_status");
    }

    @Test
    void sendMessage_pendingOnline_buffersAndKeepsPending() {
        when(sessionMapper.selectLatestByUser(USER)).thenReturn(
                session(7L, AiSupportSessionStatus.PENDING_HUMAN_VALUE));
        when(sessionMapper.touchActive(7L)).thenReturn(1);
        stubMessageInsertAssigningId(55L);
        presence.online = true;

        SupportSendResult r = service().sendMessage(USER, "等接入时先说一句", null);

        verify(sessionMapper, never()).casPendingToMessageLeft(anyLong());
        assertEquals(AiSupportSessionStatus.PENDING_HUMAN_VALUE, r.getStatus(),
                "H6：有人在线 → 缓冲保持 pending_human，接入时回放");
    }

    @Test
    void sendMessage_humanActive_appendsAndKeepsHumanActive() {
        when(sessionMapper.selectLatestByUser(USER)).thenReturn(
                session(7L, AiSupportSessionStatus.HUMAN_ACTIVE_VALUE));
        when(sessionMapper.touchActive(7L)).thenReturn(1);
        stubMessageInsertAssigningId(56L);

        SupportSendResult r = service().sendMessage(USER, "还有个问题", null);

        verify(sessionMapper, never()).casPendingToMessageLeft(anyLong());
        assertEquals(AiSupportSessionStatus.HUMAN_ACTIVE_VALUE, r.getStatus(), "H6b：实时推工作台，状态不变");
    }

    @Test
    void sendMessage_messageLeft_appendsAndKeepsMessageLeft() {
        when(sessionMapper.selectLatestByUser(USER)).thenReturn(
                session(7L, AiSupportSessionStatus.MESSAGE_LEFT_VALUE));
        when(sessionMapper.touchActive(7L)).thenReturn(1);
        stubMessageInsertAssigningId(57L);

        SupportSendResult r = service().sendMessage(USER, "补充一下地址", null);

        verify(sessionMapper, never()).casPendingToMessageLeft(anyLong());
        assertEquals(AiSupportSessionStatus.MESSAGE_LEFT_VALUE, r.getStatus(), "H6c：留言期追加，仍在留言态");
    }

    @Test
    void sendMessage_duplicateClientMsgId_returnsDuplicate_withoutPersistOrRateLimit() {
        when(sessionMapper.selectLatestByUser(USER)).thenReturn(
                session(7L, AiSupportSessionStatus.HUMAN_ACTIVE_VALUE));
        when(sessionMapper.touchActive(7L)).thenReturn(1);
        stubMessageInsertAssigningId(58L);
        AiSupportSessionServiceImpl svc = service();

        SupportSendResult first = svc.sendMessage(USER, "重复发送测试", "cid-1");
        SupportSendResult second = svc.sendMessage(USER, "重复发送测试", "cid-1");

        assertFalse(first.isDuplicate());
        assertTrue(second.isDuplicate(), "P6：5s 内同 cid 重发 → 200 不重落库");
        assertEquals(first.getMessageId(), second.getMessageId());

        // 只落库一次、只计一次限流
        verify(messageMapper).insert(any(AiSupportMessage.class));
        verify(rateLimiter).allow(USER);
    }

    @Test
    void sendMessage_duplicateHit_doesNotConsumeRateLimit() {
        when(sessionMapper.selectLatestByUser(USER)).thenReturn(
                session(7L, AiSupportSessionStatus.HUMAN_ACTIVE_VALUE));
        when(sessionMapper.touchActive(7L)).thenReturn(1);
        stubMessageInsertAssigningId(58L);
        AiSupportSessionServiceImpl svc = service();
        svc.sendMessage(USER, "第一次", "cid-9");

        svc.sendMessage(USER, "第一次", "cid-9");

        verify(rateLimiter).allow(USER); // 幂等命中在限流之前返回
    }

    @Test
    void sendMessage_rateLimited_429_andDoesNotPersist() {
        when(sessionMapper.selectLatestByUser(USER)).thenReturn(
                session(7L, AiSupportSessionStatus.HUMAN_ACTIVE_VALUE));
        when(rateLimiter.allow(USER)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().sendMessage(USER, "刷屏", null));

        assertEquals(429, ex.getCode());
        assertEquals(AiSupportSessionServiceImpl.SUPPORT_RATE_LIMITED_MESSAGE, ex.getMessage());
        verify(messageMapper, never()).insert(any(AiSupportMessage.class));
    }

    @Test
    void sendMessage_touchActiveZero_concurrentClose_409_noOrphanMessage() {
        // 并发：坐席刚把会话 closed（touchActive 带 status IN PH 条件 → 0 行）→ 不落孤儿消息
        when(sessionMapper.selectLatestByUser(USER)).thenReturn(
                session(7L, AiSupportSessionStatus.HUMAN_ACTIVE_VALUE));
        when(sessionMapper.touchActive(7L)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().sendMessage(USER, "撞上关闭", null));

        assertEquals(409, ex.getCode());
        verify(messageMapper, never()).insert(any(AiSupportMessage.class));
    }

    @Test
    void sendMessage_blankContent_400() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().sendMessage(USER, "   ", null));

        assertEquals(400, ex.getCode());
        verify(sessionMapper, never()).selectLatestByUser(anyLong());
    }

    @Test
    void sendMessage_overMaxLength_400() {
        String tooLong = "长".repeat(properties.getSupport().getUserContentMax() + 1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().sendMessage(USER, tooLong, null));

        assertEquals(400, ex.getCode());
        verify(messageMapper, never()).insert(any(AiSupportMessage.class));
    }

    @Test
    void sendMessage_contentIsTrimmedBeforePersist() {
        when(sessionMapper.selectLatestByUser(USER)).thenReturn(
                session(7L, AiSupportSessionStatus.HUMAN_ACTIVE_VALUE));
        when(sessionMapper.touchActive(7L)).thenReturn(1);
        stubMessageInsertAssigningId(59L);

        service().sendMessage(USER, "  前后有空格  ", null);

        assertEquals("前后有空格", capturedMessageInsert().getContent());
    }

    // ------------------------------------------------------------ 会话回读（H20~H23）

    @Test
    void snapshot_noSession_existsFalse() {
        when(sessionMapper.selectLatestByUser(USER)).thenReturn(null);

        AiSupportSessionVO vo = service().snapshot(USER);

        assertFalse(vo.isExists(), "H23：无人工会话行 = 纯 AI 态");
        assertNull(vo.getStatus());
        assertTrue(vo.getMessages().isEmpty());
    }

    @Test
    void snapshot_pending_returnsAllMessagesAscending() {
        AiSupportSession s = session(7L, AiSupportSessionStatus.PENDING_HUMAN_VALUE);
        when(sessionMapper.selectLatestByUser(USER)).thenReturn(s);
        when(messageMapper.selectBySessionAsc(7L)).thenReturn(List.of(
                message(1L, AiSupportSender.USER_VALUE, "第一条"),
                message(2L, AiSupportSender.SYSTEM_VALUE, "坐席繁忙提示"),
                message(3L, AiSupportSender.USER_VALUE, "补充")));

        AiSupportSessionVO vo = service().snapshot(USER);

        assertTrue(vo.isExists());
        assertEquals(7L, vo.getSessionId());
        assertEquals(AiSupportSessionStatus.PENDING_HUMAN_VALUE, vo.getStatus());
        assertEquals("等待接入", vo.getStatusName());
        assertEquals(List.of("第一条", "坐席繁忙提示", "补充"),
                vo.getMessages().stream().map(AiSupportMessageVO::getContent).toList(),
                "H20：全量正序，接入前缓冲消息不丢");
        assertNull(vo.getClosedAt());
    }

    @Test
    void snapshot_closed_keepsTerminalStateAndHistory() {
        // H21：SSE 断开期间坐席回复并 closed → 重开回读仍得终态 + 全量历史
        AiSupportSession s = session(7L, AiSupportSessionStatus.CLOSED_VALUE);
        s.setClosedAt(LocalDateTime.now());
        when(sessionMapper.selectLatestByUser(USER)).thenReturn(s);
        when(messageMapper.selectBySessionAsc(7L)).thenReturn(List.of(
                message(1L, AiSupportSender.USER_VALUE, "问题"),
                message(2L, AiSupportSender.AGENT_VALUE, "已处理"),
                message(3L, AiSupportSender.SYSTEM_VALUE, "本次人工会话已结束")));

        AiSupportSessionVO vo = service().snapshot(USER);

        assertEquals(AiSupportSessionStatus.CLOSED_VALUE, vo.getStatus(),
                "终态由 status 表达，无独立 closed 布尔");
        assertEquals("已结束", vo.getStatusName());
        assertNotNull(vo.getClosedAt());
        assertEquals(3, vo.getMessages().size(), "历史可回溯，不依赖实时推送");
    }

    @Test
    void snapshot_messageVo_doesNotExposeReadByAgent() {
        // J 决议：已读回执不外露给买家 —— 字段缺失是协议的一部分，防后人"顺手补上"
        Set<String> fields = Arrays.stream(AiSupportMessageVO.class.getDeclaredFields())
                .map(Field::getName).collect(Collectors.toSet());

        assertFalse(fields.contains("readByAgent"), "买家侧 VO 不得含 readByAgent");
        assertTrue(fields.containsAll(Set.of("messageId", "sender", "content", "createdAt")));
    }

    // ------------------------------------------------------------ 结构断言

    @Test
    void constructor_hasNoLlmDependency() {
        // 人工态不经 LLM 是 REQ §4.4 硬规则：用构造参数集合做编译期级保证，
        // 防止后人"顺手"注入 ChatModel/检索/问题池把 AI 逻辑接进人工通道
        Constructor<?> ctor = AiSupportSessionServiceImpl.class.getDeclaredConstructors()[0];
        Set<String> paramTypes = Arrays.stream(ctor.getParameterTypes())
                .map(Class::getSimpleName).collect(Collectors.toSet());

        assertEquals(Set.of("AiSupportSessionMapper", "AiSupportMessageMapper", "SupportRateLimiter",
                        "SupportDedupStore", "SupportSeatPresence", "SupportEventPublisher",
                        "SupportUserMasker", "AiProperties"),
                paramTypes, "人工态服务的依赖必须限定为会话存储 + 限流 + 幂等 + 坐席接缝 + 事件推送 + 脱敏 + 配置");
    }

    @Test
    void request_newSession_notifiesWorkbench() {
        when(sessionMapper.selectActiveByUser(USER)).thenReturn(null);
        stubSessionInsertAssigningId(9L);

        service().request(USER, AiSupportOrigin.USER_REQUEST_VALUE);

        // §4.8 session_new：新会话进入待接入 → 排队列表新增 + 提醒（H13）
        assertEquals(List.of(SupportEvents.SESSION_NEW), publisher.broadcastTypes());
        Map<String, Object> payload = publisher.broadcast.get(0).payload();
        assertEquals(9L, ((Number) payload.get("sessionId")).longValue());
        assertEquals(AiSupportOrigin.USER_REQUEST_VALUE, payload.get("origin"));
    }

    @Test
    void request_idempotentReuse_doesNotResendSessionNew() {
        // 幂等复用：工作台早就有这条会话，再发一次 session_new 会让排队列表凭空多出一条
        when(sessionMapper.selectActiveByUser(USER)).thenReturn(
                session(9L, AiSupportSessionStatus.PENDING_HUMAN_VALUE));

        service().request(USER, AiSupportOrigin.USER_REQUEST_VALUE);

        assertTrue(publisher.broadcast.isEmpty());
    }

    @Test
    void sessionStatus_humanTakeoverSet_excludesClosedAndAiActive() {
        assertTrue(AiSupportSessionStatus.isHumanTakeover(AiSupportSessionStatus.PENDING_HUMAN_VALUE));
        assertTrue(AiSupportSessionStatus.isHumanTakeover(AiSupportSessionStatus.HUMAN_ACTIVE_VALUE));
        assertTrue(AiSupportSessionStatus.isHumanTakeover(AiSupportSessionStatus.MESSAGE_LEFT_VALUE));
        assertFalse(AiSupportSessionStatus.isHumanTakeover(AiSupportSessionStatus.CLOSED_VALUE));
        assertFalse(AiSupportSessionStatus.isHumanTakeover(null));
        // ai_active 不在枚举内（隐含态不落库）：任何值都不应被当成可落库状态
        assertNull(AiSupportSessionStatus.fromValueOrNull("ai_active"),
                "ai_active 是隐含态，不得成为可落库状态");
    }

    private AiSupportMessage message(long id, String sender, String content) {
        AiSupportMessage m = new AiSupportMessage();
        m.setId(id);
        m.setSessionId(7L);
        m.setSender(sender);
        m.setContent(content);
        m.setReadByAgent(Boolean.FALSE);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }
}
