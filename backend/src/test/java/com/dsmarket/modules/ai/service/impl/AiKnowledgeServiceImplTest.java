package com.dsmarket.modules.ai.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.util.VectorText;
import com.dsmarket.modules.ai.dto.AiKnowledgeFormDTO;
import com.dsmarket.modules.ai.dto.AiKnowledgeQuery;
import com.dsmarket.modules.ai.dto.AiKnowledgeVO;
import com.dsmarket.modules.ai.entity.AiKnowledge;
import com.dsmarket.modules.ai.enums.AiKnowledgeStatus;
import com.dsmarket.modules.ai.mapper.AiKnowledgeMapper;
import com.dsmarket.modules.ai.provider.EmbeddingClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiKnowledgeService 状态机与发布向量化（C2-F5/F6）：确定性，EmbeddingClient 为 stub，不触网。
 * 覆盖：create 强制 draft；发布需向量化成功才置 published；向量化失败保持原状态；编辑已发布回退 draft；前置态校验。
 */
class AiKnowledgeServiceImplTest {

    private static final Long ID = 10L;

    private AiKnowledgeMapper mapper = mock(AiKnowledgeMapper.class);
    private EmbeddingClient embed = mock(EmbeddingClient.class);
    private AiKnowledgeServiceImpl service = new AiKnowledgeServiceImpl(mapper, embed);

    private AiKnowledge draftRow() {
        AiKnowledge row = new AiKnowledge();
        row.setId(ID);
        row.setCategory("after_sale");
        row.setQuestion("七天无理由退货怎么退");
        row.setAnswer("签收后7天内可申请退货退款。");
        row.setStatus(AiKnowledgeStatus.DRAFT_VALUE);
        return row;
    }

    private AiKnowledgeFormDTO form() {
        AiKnowledgeFormDTO f = new AiKnowledgeFormDTO();
        f.setCategory("after_sale");
        f.setQuestion("  七天无理由退货怎么退  ");
        f.setAnswer("  签收后7天内可申请退货退款。  ");
        f.setKeywords(List.of("退货", "七天", " 退货 "));
        f.setFaq(true);
        return f;
    }

    @Test
    void create_forcesDraft_andReturnsId() {
        when(mapper.insert(any(AiKnowledge.class))).thenAnswer(inv -> {
            AiKnowledge r = inv.getArgument(0);
            r.setId(ID);
            return 1;
        });
        Long id = service.create(form());
        assertEquals(ID, id);

        ArgumentCaptor<AiKnowledge> cap = ArgumentCaptor.forClass(AiKnowledge.class);
        verify(mapper).insert(cap.capture());
        AiKnowledge saved = cap.getValue();
        assertEquals(AiKnowledgeStatus.DRAFT_VALUE, saved.getStatus(), "create 一律 draft");
        assertEquals("七天无理由退货怎么退", saved.getQuestion(), "question 应 trim");
        assertEquals("签收后7天内可申请退货退款。", saved.getAnswer(), "answer 应 trim");
        assertTrue(saved.getFaq());
        // keywords 去空白 + 去重
        assertEquals(List.of("退货", "七天"), List.of(saved.getKeywords()));
    }

    @Test
    void create_nullKeywords_storesNull() {
        AiKnowledgeFormDTO f = form();
        f.setKeywords(null);
        service.create(f);
        ArgumentCaptor<AiKnowledge> cap = ArgumentCaptor.forClass(AiKnowledge.class);
        verify(mapper).insert(cap.capture());
        assertNull(cap.getValue().getKeywords());
    }

    @Test
    void publish_embedsAndSetsPublished() {
        when(mapper.selectById(ID)).thenReturn(draftRow());
        float[] vec = {0.1f, 0.2f, 0.3f};
        when(embed.embed(List.of("七天无理由退货怎么退"))).thenReturn(List.of(vec));
        when(mapper.publishWithEmbedding(any(), any())).thenReturn(1);

        service.publish(ID);

        ArgumentCaptor<String> vecCap = ArgumentCaptor.forClass(String.class);
        verify(mapper).publishWithEmbedding(org.mockito.ArgumentMatchers.eq(ID), vecCap.capture());
        assertEquals(VectorText.of(vec), vecCap.getValue(), "向量应为 pgvector 文本格式");
        verify(mapper, never()).updateById(any(AiKnowledge.class));
    }

    @Test
    void publish_embedFailure_keepsOriginalStatus_andNoWrite() {
        AiKnowledge row = draftRow();
        row.setStatus(AiKnowledgeStatus.DISABLED_VALUE);
        when(mapper.selectById(ID)).thenReturn(row);
        when(embed.embed(any())).thenThrow(new BusinessException(502, "向量化服务不可用"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.publish(ID));
        assertEquals(502, ex.getCode());
        verify(mapper, never()).publishWithEmbedding(any(), any());
        verify(mapper, never()).updateById(any(AiKnowledge.class));
    }

    @Test
    void publish_emptyVectorResult_rejected() {
        when(mapper.selectById(ID)).thenReturn(draftRow());
        when(embed.embed(any())).thenReturn(List.of()); // 上游空返回

        BusinessException ex = assertThrows(BusinessException.class, () -> service.publish(ID));
        assertEquals(500, ex.getCode());
        verify(mapper, never()).publishWithEmbedding(any(), any());
    }

    @Test
    void publish_alreadyPublished_isIdempotentNoEmbed() {
        AiKnowledge row = draftRow();
        row.setStatus(AiKnowledgeStatus.PUBLISHED_VALUE);
        when(mapper.selectById(ID)).thenReturn(row);

        service.publish(ID); // 不抛错

        verify(embed, never()).embed(any());
        verify(mapper, never()).publishWithEmbedding(any(), any());
    }

    @Test
    void publish_missingRow_throwsNotFound() {
        when(mapper.selectById(ID)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.publish(ID));
        assertEquals(404, ex.getCode());
    }

    @Test
    void update_publishedRow_revertsToDraft() {
        AiKnowledge row = draftRow();
        row.setStatus(AiKnowledgeStatus.PUBLISHED_VALUE);
        when(mapper.selectById(ID)).thenReturn(row);
        when(mapper.updateById(any(AiKnowledge.class))).thenReturn(1);

        service.update(ID, form());

        ArgumentCaptor<AiKnowledge> cap = ArgumentCaptor.forClass(AiKnowledge.class);
        verify(mapper).updateById(cap.capture());
        assertEquals(AiKnowledgeStatus.DRAFT_VALUE, cap.getValue().getStatus(),
                "编辑已发布条目须回退 draft（重发布即重向量化）");
        assertEquals("七天无理由退货怎么退", cap.getValue().getQuestion());
    }

    @Test
    void update_draftRow_keepsDraft() {
        when(mapper.selectById(ID)).thenReturn(draftRow());
        service.update(ID, form());
        ArgumentCaptor<AiKnowledge> cap = ArgumentCaptor.forClass(AiKnowledge.class);
        verify(mapper).updateById(cap.capture());
        assertEquals(AiKnowledgeStatus.DRAFT_VALUE, cap.getValue().getStatus());
    }

    @Test
    void update_missingRow_throwsNotFound() {
        when(mapper.selectById(ID)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.update(ID, form()));
        assertEquals(404, ex.getCode());
    }

    @Test
    void unpublish_onlyFromPublished() {
        when(mapper.selectById(ID)).thenReturn(draftRow()); // draft
        BusinessException ex = assertThrows(BusinessException.class, () -> service.unpublish(ID));
        assertEquals(409, ex.getCode(), "非 published 不可停用");

        AiKnowledge published = draftRow();
        published.setStatus(AiKnowledgeStatus.PUBLISHED_VALUE);
        when(mapper.selectById(ID)).thenReturn(published);
        service.unpublish(ID);
        ArgumentCaptor<AiKnowledge> cap = ArgumentCaptor.forClass(AiKnowledge.class);
        verify(mapper).updateById(cap.capture());
        assertEquals(AiKnowledgeStatus.DISABLED_VALUE, cap.getValue().getStatus());
    }

    @Test
    void delete_missingRow_throwsNotFound() {
        when(mapper.selectById(ID)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.delete(ID));
        assertEquals(404, ex.getCode());
        verify(mapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void delete_exists_softDeletes() {
        when(mapper.selectById(ID)).thenReturn(draftRow());
        service.delete(ID);
        verify(mapper).deleteById((java.io.Serializable) ID);
    }

    @Test
    void adminPage_mapsVO_andEmbeddingFlag() {
        AiKnowledge p1 = draftRow();
        p1.setStatus(AiKnowledgeStatus.PUBLISHED_VALUE);
        p1.setFaq(true);
        p1.setKeywords(new String[]{"退货"});
        AiKnowledge p2 = draftRow();
        p2.setId(11L);
        p2.setStatus(AiKnowledgeStatus.DRAFT_VALUE);

        Page<AiKnowledge> page = new Page<>(1, 20);
        page.setTotal(2);
        page.setRecords(List.of(p1, p2));
        when(mapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(mapper.selectEmbeddedIds(List.of(10L, 11L))).thenReturn(List.of(10L)); // p1 有向量

        AiKnowledgeQuery q = new AiKnowledgeQuery();
        q.setStatus(AiKnowledgeStatus.PUBLISHED_VALUE);
        PageResult<AiKnowledgeVO> result = service.adminPage(1, 20, q);

        assertEquals(2, result.getTotal());
        AiKnowledgeVO vo1 = result.getRecords().get(0);
        assertEquals("已发布", vo1.getStatusName());
        assertEquals("售后", vo1.getCategoryName());
        assertEquals(List.of("退货"), vo1.getKeywords());
        assertTrue(vo1.getEmbeddingFilled(), "published 且向量已填 → true");
        assertFalse(result.getRecords().get(1).getEmbeddingFilled(), "draft 无向量 → false");
    }

    @Test
    void detail_reportsEmbeddingState() {
        when(mapper.selectById(ID)).thenReturn(draftRow());
        when(mapper.selectEmbeddedIds(List.of(ID))).thenReturn(List.of());
        AiKnowledgeVO vo = service.detail(ID);
        assertFalse(vo.getEmbeddingFilled());
    }
}
