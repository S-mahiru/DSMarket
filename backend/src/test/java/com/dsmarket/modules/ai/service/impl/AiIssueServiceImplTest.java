package com.dsmarket.modules.ai.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.modules.ai.config.AiProperties;
import com.dsmarket.modules.ai.dto.AiIssueVO;
import com.dsmarket.modules.ai.dto.IssueAdoptRequest;
import com.dsmarket.modules.ai.entity.AiIssuePool;
import com.dsmarket.modules.ai.entity.AiKnowledge;
import com.dsmarket.modules.ai.enums.AiIssueSource;
import com.dsmarket.modules.ai.enums.AiIssueStatus;
import com.dsmarket.modules.ai.enums.AiKnowledgeStatus;
import com.dsmarket.modules.ai.mapper.AiIssuePoolMapper;
import com.dsmarket.modules.ai.mapper.AiKnowledgeMapper;
import com.dsmarket.modules.ai.model.ChatRound;
import com.dsmarket.modules.ai.provider.EmbeddingClient;
import com.dsmarket.modules.ai.session.AiSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiIssueServiceImpl 采集 + R3 复核（C3 切片 1 + 切片 2）单测。
 *
 * <p>纯 JUnit5 + 手写 Mockito（mock 接口，免 byte-buddy agent）。切片 1 覆盖 C3-F1/F2
 * （幂等/停采/回查/静默），切片 2 覆盖 F3 列表映射、F4 采纳（行锁/写知识 draft/同步向量化/
 * 失败留稿、ignored 409、幂等）、F5 忽略、F6 兜底（成功发布 / 失败计数 / 超上限告警）。</p>
 */
class AiIssueServiceImplTest {

    private static final long USER = 200L;

    private AiIssuePoolMapper issueMapper;
    private AiSessionStore sessionStore;
    private AiKnowledgeMapper knowledgeMapper;
    private EmbeddingClient embedding;
    private AiProperties properties;
    private AiIssueServiceImpl service;

    @BeforeEach
    void setUp() {
        issueMapper = mock(AiIssuePoolMapper.class);
        sessionStore = mock(AiSessionStore.class);
        knowledgeMapper = mock(AiKnowledgeMapper.class);
        embedding = mock(EmbeddingClient.class);
        properties = new AiProperties(); // 真配置：issue.retryMax 默认 5
        service = new AiIssueServiceImpl(issueMapper, sessionStore, knowledgeMapper, embedding, properties);
    }

    // ------------------------------------------------------------ 通用构造

    private ChatRound round(String replyId, String user) {
        return ChatRound.builder().replyId(replyId).userContent(user).assistantContent("答").ts(1L).build();
    }

    private AiIssuePool issueRow(long id, String status, String source, String question) {
        AiIssuePool p = new AiIssuePool();
        p.setId(id);
        p.setUserId(USER);
        p.setStatus(status);
        p.setSource(source);
        p.setQuestion(question);
        return p;
    }

    private IssueAdoptRequest adoptReq() {
        IssueAdoptRequest r = new IssueAdoptRequest();
        r.setCategory("after_sale");
        r.setAnswer("签收后7天内可申请退货退款。");
        return r;
    }

    private AiIssuePool capturedInsert() {
        ArgumentCaptor<AiIssuePool> cap = ArgumentCaptor.forClass(AiIssuePool.class);
        verify(issueMapper).insert((AiIssuePool) cap.capture());
        return cap.getValue();
    }

    private AiKnowledge capturedKnowledgeInsert() {
        ArgumentCaptor<AiKnowledge> cap = ArgumentCaptor.forClass(AiKnowledge.class);
        verify(knowledgeMapper).insert((AiKnowledge) cap.capture());
        return cap.getValue();
    }

    private void stubKnowledgeInsertAssigningId(long kid) {
        when(knowledgeMapper.insert(any(AiKnowledge.class))).thenAnswer(inv -> {
            AiKnowledge k = inv.getArgument(0);
            k.setId(kid);
            return 1;
        });
    }

    // ------------------------------------------------------------ C3-F1 低置信采集

    @Test
    void lowConfidence_firstCollect_insertsPendingLowConf() {
        when(issueMapper.countByUserQuestion(USER, "预售能退吗")).thenReturn(0);
        when(issueMapper.countAdoptedOrIgnoredByQuestion("预售能退吗")).thenReturn(0);

        service.collectLowConfidence(USER, "预售能退吗");

        AiIssuePool p = capturedInsert();
        assertEquals(USER, p.getUserId());
        assertEquals("预售能退吗", p.getQuestion());
        assertEquals(AiIssueSource.LOW_CONF_VALUE, p.getSource());
        assertEquals(AiIssueStatus.PENDING_VALUE, p.getStatus());
        assertNull(p.getReplyId());
    }

    @Test
    void lowConfidence_questionTrimmedBeforeInsert() {
        when(issueMapper.countByUserQuestion(USER, "预售能退吗")).thenReturn(0);
        when(issueMapper.countAdoptedOrIgnoredByQuestion("预售能退吗")).thenReturn(0);

        service.collectLowConfidence(USER, "  预售能退吗  ");

        assertEquals("预售能退吗", capturedInsert().getQuestion(), "入库前应 trim");
    }

    @Test
    void lowConfidence_sameUserSameQuestionExists_skips() {
        when(issueMapper.countByUserQuestion(USER, "预售能退吗")).thenReturn(1);

        service.collectLowConfidence(USER, "预售能退吗");

        verify(issueMapper, never()).insert(any(AiIssuePool.class));
    }

    @Test
    void lowConfidence_questionAlreadyAdoptedGlobally_skips() {
        when(issueMapper.countByUserQuestion(USER, "预售能退吗")).thenReturn(0);
        when(issueMapper.countAdoptedOrIgnoredByQuestion("预售能退吗")).thenReturn(1);

        service.collectLowConfidence(USER, "预售能退吗");

        verify(issueMapper, never()).insert(any(AiIssuePool.class));
    }

    @Test
    void lowConfidence_blankQuestion_doesNothing() {
        service.collectLowConfidence(USER, "   ");
        verify(issueMapper, never()).countByUserQuestion(any(), any());
        verify(issueMapper, never()).insert(any(AiIssuePool.class));
    }

    @Test
    void lowConfidence_mapperFailure_swallowed() {
        when(issueMapper.countByUserQuestion(USER, "预售能退吗"))
                .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.collectLowConfidence(USER, "预售能退吗"));
    }

    // ------------------------------------------------------------ C3-F2 点踩采集

    @Test
    void dislike_roundFound_insertsPendingDislikeWithOriginalQuestion() {
        when(sessionStore.loadRounds(USER)).thenReturn(List.of(round("7", "七天无理由能退吗")));
        when(issueMapper.countByUserReplyAndQuestion(USER, "7", "七天无理由能退吗")).thenReturn(0);
        when(issueMapper.countAdoptedOrIgnoredByQuestion("七天无理由能退吗")).thenReturn(0);

        service.collectDislike(USER, "7");

        AiIssuePool p = capturedInsert();
        assertEquals(USER, p.getUserId());
        assertEquals("七天无理由能退吗", p.getQuestion(), "question=会话该轮原问句 userContent");
        assertEquals(AiIssueSource.DISLIKE_VALUE, p.getSource());
        assertEquals("7", p.getReplyId());
        assertEquals(AiIssueStatus.PENDING_VALUE, p.getStatus());
    }

    @Test
    void dislike_roundNotFound_silentIgnore_noInsert() {
        when(sessionStore.loadRounds(USER)).thenReturn(List.of(round("1", "另一问")));

        service.collectDislike(USER, "99");

        verify(issueMapper, never()).insert(any(AiIssuePool.class));
        verify(issueMapper, never()).countAdoptedOrIgnoredByQuestion(any());
        verify(issueMapper, never()).countByUserReplyAndQuestion(any(), any(), any());
    }

    @Test
    void dislike_sameReplySameQuestion_skips() {
        when(sessionStore.loadRounds(USER)).thenReturn(List.of(round("7", "七天无理由能退吗")));
        when(issueMapper.countByUserReplyAndQuestion(USER, "7", "七天无理由能退吗")).thenReturn(1);

        service.collectDislike(USER, "7");

        verify(issueMapper, never()).insert(any(AiIssuePool.class));
    }

    /**
     * ★ #127 核心回归：跨会话 `replyId` 回收后，**新问句**不得被旧会话的同 replyId 点踩挡下。
     *
     * <p>旧实现按 `(user_id, reply_id)` 判重（且查询不带 status，含已 adopted/ignored），
     * 于是"新会话第 1 轮的新问题"会被"旧会话第 1 轮点过的踩"永久判为重复 → 静默丢弃（漏采）。
     * 本用例锁住修复：幂等查询必须带上**本轮的**问句，桩按不同问句返回 0 时必须入池。</p>
     */
    @Test
    void dislike_sameReplyDifferentQuestion_127_stillCollected() {
        // 本会话第 "1" 轮问的是「在吗在吗」；池里那条 DISLIKE 是**旧会话**第 "1" 轮留下的「你好」。
        // 桩按问句作答（只有问到「你好」才算重复）—— 这精确复刻了修复前"只按 replyId 判重"误挡的情形。
        // ⚠️ 若把这行写成 `thenReturn(0)`，本用例就退化成空断言（旧代码也会通过）：
        //    返回 0 时没有任何"撞号"被模拟，测的只是"底层说没重复就入池"这个恒真命题。
        when(sessionStore.loadRounds(USER)).thenReturn(List.of(round("1", "在吗在吗")));
        when(issueMapper.countByUserReplyAndQuestion(eq(USER), eq("1"), anyString()))
                .thenAnswer(inv -> "你好".equals(inv.getArgument(2, String.class)) ? 1 : 0);
        when(issueMapper.countAdoptedOrIgnoredByQuestion("在吗在吗")).thenReturn(0);

        service.collectDislike(USER, "1");

        // ① 判重必须问的是**本轮**问句 —— 问错一个字符串就等于退回 #127 的旧语义
        verify(issueMapper).countByUserReplyAndQuestion(USER, "1", "在吗在吗");
        // ② 且必须真的入池
        AiIssuePool p = capturedInsert();
        assertEquals("1", p.getReplyId());
        assertEquals("在吗在吗", p.getQuestion(), "跨会话撞号时采集的必须是**本轮**问句，不是旧会话那条");
    }

    @Test
    void dislike_questionAlreadyDisposedGlobally_skips() {
        when(sessionStore.loadRounds(USER)).thenReturn(List.of(round("7", "七天无理由能退吗")));
        when(issueMapper.countByUserReplyAndQuestion(USER, "7", "七天无理由能退吗")).thenReturn(0);
        when(issueMapper.countAdoptedOrIgnoredByQuestion("七天无理由能退吗")).thenReturn(1);

        service.collectDislike(USER, "7");

        verify(issueMapper, never()).insert(any(AiIssuePool.class));
    }

    @Test
    void dislike_blankReplyId_doesNothing() {
        service.collectDislike(USER, "   ");
        verify(issueMapper, never()).countByUserReplyAndQuestion(any(), any(), any());
        verify(issueMapper, never()).insert(any(AiIssuePool.class));
    }

    @Test
    void dislike_blankRoundContent_silentIgnore() {
        when(sessionStore.loadRounds(USER)).thenReturn(List.of(
                ChatRound.builder().replyId("7").userContent("  ").assistantContent("答").ts(1L).build()));

        service.collectDislike(USER, "7");

        verify(issueMapper, never()).insert(any(AiIssuePool.class));
    }

    @Test
    void dislike_mapperFailure_swallowed() {
        when(sessionStore.loadRounds(USER)).thenReturn(List.of(round("7", "问")));
        when(issueMapper.countByUserReplyAndQuestion(USER, "7", "问")).thenReturn(0);
        when(issueMapper.countAdoptedOrIgnoredByQuestion("问"))
                .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service.collectDislike(USER, "7"));
    }

    // ------------------------------------------- C3-F2 返回值 → C4-F6 触发③ 未解决计数

    @Test
    void dislike_freshClick_returnsTrue_soCallerCountsIt() {
        when(sessionStore.loadRounds(USER)).thenReturn(List.of(round("7", "七天无理由能退吗")));
        when(issueMapper.countByUserReplyAndQuestion(USER, "7", "七天无理由能退吗")).thenReturn(0);
        when(issueMapper.countAdoptedOrIgnoredByQuestion("七天无理由能退吗")).thenReturn(0);

        assertTrue(service.collectDislike(USER, "7"),
                "新鲜点踩 = 一次未解决信号，调用方据此累加计数（F6 触发③）");
    }

    @Test
    void dislike_repeatClick_returnsFalse_blocksCounterInflation() {
        // 连点虚增的防线就复用这里：同 user+reply+问句 幂等判定已经把重复点击挡住了，
        // 调用方拿 false 就不计数 —— 不需要另写一套去重。
        when(sessionStore.loadRounds(USER)).thenReturn(List.of(round("7", "七天无理由能退吗")));
        when(issueMapper.countByUserReplyAndQuestion(USER, "7", "七天无理由能退吗")).thenReturn(1);

        assertFalse(service.collectDislike(USER, "7"), "重复点踩不得让未解决计数虚增");
    }

    /**
     * ★ #127 在"未解决计数"侧的表现：跨会话撞号的新问句必须算一次新鲜点踩（返回 true）。
     *
     * <p>修复前这里返回 false ⇒ `AiController.feedback` 不累加未解决计数 ⇒
     * C4 §4.6 触发③（≥2 → suggest(UNRESOLVED)）迟一拍。与上面那条入池断言是两个独立的可观察量。</p>
     */
    @Test
    void dislike_sameReplyDifferentQuestion_127_countsAsFreshSignal() {
        when(sessionStore.loadRounds(USER)).thenReturn(List.of(round("1", "在吗在吗")));
        when(issueMapper.countByUserReplyAndQuestion(eq(USER), eq("1"), anyString()))
                .thenAnswer(inv -> "你好".equals(inv.getArgument(2, String.class)) ? 1 : 0);
        when(issueMapper.countAdoptedOrIgnoredByQuestion("在吗在吗")).thenReturn(0);

        assertTrue(service.collectDislike(USER, "1"),
                "撞号≠重复：买家这一轮确实没被解决，信号不得被旧会话吞掉");
        // 单独断言返回值是不够的：变异（把问句参数传成 replyId）下**只看返回值会侥幸通过** ——
        // 拿不到「你好」就返回 0、照样是 true。故必须同时锁住"判重问的是哪个问句"。
        verify(issueMapper).countByUserReplyAndQuestion(USER, "1", "在吗在吗");
    }

    @Test
    void dislike_roundNotFound_returnsFalse_cannotAttribute() {
        // 回查不到（超窗/伪造 replyId）→ 归因不到具体轮次，无法确认"本轮未解决"，宁可少数
        when(sessionStore.loadRounds(USER)).thenReturn(List.of(round("1", "另一问")));

        assertFalse(service.collectDislike(USER, "99"));
    }

    @Test
    void dislike_questionAlreadyDisposed_stillReturnsTrue_buyerStillUnresolved() {
        // 口径分叉（有意）：问题池**停采**（已处置），但买家**此刻仍然没被解决** → 计数照样 +1。
        // 问题池管"还要不要收进池子"，未解决计数管"买家还卡着没卡着"，两者不联动。
        when(sessionStore.loadRounds(USER)).thenReturn(List.of(round("7", "七天无理由能退吗")));
        when(issueMapper.countByUserReplyAndQuestion(USER, "7", "七天无理由能退吗")).thenReturn(0);
        when(issueMapper.countAdoptedOrIgnoredByQuestion("七天无理由能退吗")).thenReturn(1);

        assertTrue(service.collectDislike(USER, "7"),
                "问题池停采 ≠ 买家被解决了；计数问的是买家状态");
    }

    @Test
    void dislike_mapperFailure_returnsFalse_notThrow() {
        when(sessionStore.loadRounds(USER)).thenReturn(List.of(round("7", "问")));
        when(issueMapper.countByUserReplyAndQuestion(USER, "7", "问")).thenReturn(0);
        when(issueMapper.countAdoptedOrIgnoredByQuestion("问"))
                .thenThrow(new RuntimeException("db down"));

        assertFalse(service.collectDislike(USER, "7"),
                "采集失败不计数：宁可少发一次气泡，也不让异常冒到主流程");
    }

    @Test
    void dislike_blankReplyId_returnsFalse() {
        assertFalse(service.collectDislike(USER, "   "));
    }

    // ------------------------------------------------------------ C3-F3 后台列表

    @Test
    void adminPage_mapsVO_withSourceNameStatusName_andFilters() {
        AiIssuePool p1 = issueRow(1, AiIssueStatus.PENDING_VALUE, AiIssueSource.LOW_CONF_VALUE, "预售能退吗");
        AiIssuePool p2 = issueRow(2, AiIssueStatus.ADOPTED_VALUE, AiIssueSource.DISLIKE_VALUE, "七天无理由能退吗");
        p2.setRelatedKnowledgeId(9L);
        p2.setReplyId("7");
        p2.setRetryCount(0);

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<AiIssuePool> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20);
        page.setTotal(2);
        page.setRecords(List.of(p1, p2));
        when(issueMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<AiIssueVO> result = service.adminPage(1, 20, AiIssueStatus.PENDING_VALUE, null);

        assertEquals(2, result.getTotal());
        AiIssueVO vo1 = result.getRecords().get(0);
        assertEquals("低置信", vo1.getSourceName());
        assertEquals("待处理", vo1.getStatusName());
        assertEquals("预售能退吗", vo1.getQuestion());
        AiIssueVO vo2 = result.getRecords().get(1);
        assertEquals("点踩", vo2.getSourceName());
        assertEquals("已采纳", vo2.getStatusName());
        assertEquals(9L, vo2.getRelatedKnowledgeId());
    }

    // ------------------------------------------------------------ C3-F4 采纳

    @Test
    void adopt_embedSuccess_publishesAndAdopts() {
        AiIssuePool issue = issueRow(1, AiIssueStatus.PENDING_VALUE, AiIssueSource.LOW_CONF_VALUE, "七天无理由能退吗");
        when(issueMapper.lockById(1L)).thenReturn(issue);
        stubKnowledgeInsertAssigningId(123L);
        float[] vec = {0.1f, 0.2f, 0.3f};
        when(embedding.embed(List.of("七天无理由能退吗"))).thenReturn(List.of(vec));
        when(knowledgeMapper.publishWithEmbedding(eq(123L), anyString())).thenReturn(1);
        when(issueMapper.casAdopt(1L)).thenReturn(1);

        Map<String, Object> r = service.adopt(1L, adoptReq());

        assertEquals(true, r.get("ok"));
        assertEquals(123L, r.get("knowledgeId"));
        assertEquals(true, r.get("published"), "同步向量化成功 → 直接发布");

        AiKnowledge saved = capturedKnowledgeInsert();
        assertEquals(AiKnowledgeStatus.DRAFT_VALUE, saved.getStatus(), "采纳先写 draft，再经发布置 published");
        assertEquals("七天无理由能退吗", saved.getQuestion(), "question 取问题池原问句");
        assertEquals("签收后7天内可申请退货退款。", saved.getAnswer(), "answer 应 trim");
        assertEquals("after_sale", saved.getCategory());
        assertEquals(false, saved.getFaq(), "采纳写知识默认不参与 FAQ 精选");
        verify(issueMapper).linkKnowledge(1L, 123L);
        verify(issueMapper).casAdopt(1L);
    }

    @Test
    void adopt_embedEmpty_keepsDraftPending_returnsUnpublished() {
        AiIssuePool issue = issueRow(1, AiIssueStatus.PENDING_VALUE, AiIssueSource.LOW_CONF_VALUE, "七天无理由能退吗");
        when(issueMapper.lockById(1L)).thenReturn(issue);
        stubKnowledgeInsertAssigningId(123L);
        when(embedding.embed(any())).thenReturn(List.of()); // 上游空返回

        Map<String, Object> r = service.adopt(1L, adoptReq());

        assertEquals(true, r.get("ok"));
        assertEquals(123L, r.get("knowledgeId"));
        assertEquals(false, r.get("published"), "向量化空返回 → 已受理待 F6");
        verify(issueMapper).linkKnowledge(1L, 123L);          // draft + 回填保留
        verify(issueMapper, never()).casAdopt(1L);            // issue 仍 pending
        capturedKnowledgeInsert();                            // 知识 draft 已建
    }

    @Test
    void adopt_embedThrows_keepsDraftPending_doesNotRollback() {
        AiIssuePool issue = issueRow(1, AiIssueStatus.PENDING_VALUE, AiIssueSource.LOW_CONF_VALUE, "七天无理由能退吗");
        when(issueMapper.lockById(1L)).thenReturn(issue);
        stubKnowledgeInsertAssigningId(123L);
        when(embedding.embed(any())).thenThrow(new RuntimeException("dashscope timeout"));

        Map<String, Object> r = service.adopt(1L, adoptReq());

        assertEquals(true, r.get("ok"));
        assertEquals(false, r.get("published"), "向量化异常被吞，受理不失败");
        verify(knowledgeMapper).insert(any(AiKnowledge.class)); // draft 保留（tx 提交）
        verify(issueMapper).linkKnowledge(1L, 123L);
        verify(issueMapper, never()).casAdopt(1L);
    }

    @Test
    void adopt_alreadyAdopted_isIdempotent_returnsExisting() {
        AiIssuePool issue = issueRow(1, AiIssueStatus.ADOPTED_VALUE, AiIssueSource.LOW_CONF_VALUE, "问");
        issue.setRelatedKnowledgeId(123L);
        when(issueMapper.lockById(1L)).thenReturn(issue);
        AiKnowledge published = new AiKnowledge();
        published.setId(123L);
        published.setStatus(AiKnowledgeStatus.PUBLISHED_VALUE);
        when(knowledgeMapper.selectById(123L)).thenReturn(published);

        Map<String, Object> r = service.adopt(1L, adoptReq());

        assertEquals(true, r.get("ok"));
        assertEquals(123L, r.get("knowledgeId"));
        assertEquals(true, r.get("published"));
        verify(knowledgeMapper, never()).insert(any(AiKnowledge.class));
        verify(issueMapper, never()).casAdopt(1L);
        verify(issueMapper, never()).linkKnowledge(any(), any());
    }

    @Test
    void adopt_alreadyAdopted_butKnowledgeStillDraft_returnsUnpublished() {
        AiIssuePool issue = issueRow(1, AiIssueStatus.ADOPTED_VALUE, AiIssueSource.LOW_CONF_VALUE, "问");
        issue.setRelatedKnowledgeId(123L);
        when(issueMapper.lockById(1L)).thenReturn(issue);
        AiKnowledge draft = new AiKnowledge();
        draft.setId(123L);
        draft.setStatus(AiKnowledgeStatus.DRAFT_VALUE);
        when(knowledgeMapper.selectById(123L)).thenReturn(draft);

        Map<String, Object> r = service.adopt(1L, adoptReq());

        assertEquals(false, r.get("published"), "幂等返回也要如实反映：关联知识仍是 draft（待 F6）");
    }

    @Test
    void adopt_onIgnored_conflict409() {
        AiIssuePool issue = issueRow(1, AiIssueStatus.IGNORED_VALUE, AiIssueSource.LOW_CONF_VALUE, "问");
        when(issueMapper.lockById(1L)).thenReturn(issue);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.adopt(1L, adoptReq()));
        assertEquals(409, ex.getCode(), "已忽略不可采纳");
        verify(knowledgeMapper, never()).insert(any(AiKnowledge.class));
    }

    @Test
    void adopt_missingRow_throwsNotFound404() {
        when(issueMapper.lockById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.adopt(999L, adoptReq()));
        assertEquals(404, ex.getCode());
    }

    @Test
    void adopt_concurrentDoubleAdopt_secondSeesAdopted_returnsExisting() {
        // 并发双采纳：事务串行后，第二者在行锁内复查到 adopted → 幂等返回，不重复写知识
        AiIssuePool adopted = issueRow(1, AiIssueStatus.ADOPTED_VALUE, AiIssueSource.LOW_CONF_VALUE, "问");
        adopted.setRelatedKnowledgeId(123L);
        when(issueMapper.lockById(1L)).thenReturn(adopted);
        AiKnowledge published = new AiKnowledge();
        published.setId(123L);
        published.setStatus(AiKnowledgeStatus.PUBLISHED_VALUE);
        when(knowledgeMapper.selectById(123L)).thenReturn(published);

        Map<String, Object> r = service.adopt(1L, adoptReq());

        assertEquals(123L, r.get("knowledgeId"));
        verify(knowledgeMapper, never()).insert(any(AiKnowledge.class));
    }

    // ------------------------------------------------------------ C3-F5 忽略

    @Test
    void ignore_pending_migratesToIgnored() {
        when(issueMapper.selectById(1L)).thenReturn(issueRow(1, AiIssueStatus.PENDING_VALUE,
                AiIssueSource.LOW_CONF_VALUE, "问"));

        service.ignore(1L);

        verify(issueMapper).casIgnore(1L);
    }

    @Test
    void ignore_alreadyDisposed_isIdempotentNoWrite() {
        when(issueMapper.selectById(1L)).thenReturn(issueRow(1, AiIssueStatus.ADOPTED_VALUE,
                AiIssueSource.LOW_CONF_VALUE, "问"));

        service.ignore(1L);

        verify(issueMapper, never()).casIgnore(any());
    }

    @Test
    void ignore_missingRow_throwsNotFound404() {
        when(issueMapper.selectById(1L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.ignore(1L));
        assertEquals(404, ex.getCode());
        verify(issueMapper, never()).casIgnore(any());
    }

    // ------------------------------------------------------------ C3-F6 定时兜底

    @Test
    void retry_publishSuccess_publishesAndAdopts() {
        AiIssuePool issue = issueRow(1, AiIssueStatus.PENDING_VALUE, AiIssueSource.LOW_CONF_VALUE, "七天无理由能退吗");
        issue.setRelatedKnowledgeId(9L);
        when(issueMapper.selectRetryableIssues(anyInt())).thenReturn(List.of(issue));
        when(embedding.embed(List.of("七天无理由能退吗"))).thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(knowledgeMapper.publishWithEmbedding(eq(9L), anyString())).thenReturn(1);
        when(issueMapper.casAdopt(1L)).thenReturn(1);

        int n = service.retryVectorization();

        assertEquals(1, n);
        verify(knowledgeMapper).publishWithEmbedding(eq(9L), anyString());
        verify(issueMapper).casAdopt(1L);
        verify(issueMapper, never()).incrementRetry(any());
    }

    @Test
    void retry_embedFail_incrementsRetry_noPublish() {
        AiIssuePool issue = issueRow(1, AiIssueStatus.PENDING_VALUE, AiIssueSource.LOW_CONF_VALUE, "问");
        issue.setRelatedKnowledgeId(9L);
        when(issueMapper.selectRetryableIssues(anyInt())).thenReturn(List.of(issue));
        when(embedding.embed(any())).thenThrow(new RuntimeException("dashscope down"));

        int n = assertDoesNotThrow(() -> service.retryVectorization());

        assertEquals(0, n);
        verify(issueMapper).incrementRetry(1L);
        verify(knowledgeMapper, never()).publishWithEmbedding(any(), any());
        verify(issueMapper, never()).casAdopt(any());
    }

    @Test
    void retry_reachesCap_logsWarnOnce() {
        AiIssuePool issue = issueRow(1, AiIssueStatus.PENDING_VALUE, AiIssueSource.LOW_CONF_VALUE, "问");
        issue.setRelatedKnowledgeId(9L);
        issue.setRetryCount(properties.getIssue().getRetryMax() - 1); // 距上限还差 1 次
        when(issueMapper.selectRetryableIssues(anyInt())).thenReturn(List.of(issue));
        when(embedding.embed(any())).thenThrow(new RuntimeException("dashscope down"));

        Logger logger = (Logger) LoggerFactory.getLogger(AiIssueServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.retryVectorization();
        } finally {
            logger.detachAppender(appender);
        }

        verify(issueMapper).incrementRetry(1L);
        boolean warned = appender.list.stream().anyMatch(e ->
                e.getLevel() == Level.WARN && e.getFormattedMessage().contains("已达上限"));
        assertTrue(warned, "达到 ai.issue.retry-max 上限应 log.warn 告警");
    }

    @Test
    void retry_emptyScan_noWork() {
        when(issueMapper.selectRetryableIssues(anyInt())).thenReturn(List.of());

        assertEquals(0, service.retryVectorization());
        verify(embedding, never()).embed(any());
    }
}
