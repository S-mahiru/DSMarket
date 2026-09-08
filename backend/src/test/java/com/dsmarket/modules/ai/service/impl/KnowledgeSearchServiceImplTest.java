package com.dsmarket.modules.ai.service.impl;

import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.util.VectorText;
import com.dsmarket.modules.ai.config.AiProperties;
import com.dsmarket.modules.ai.entity.AiKnowledge;
import com.dsmarket.modules.ai.mapper.AiKnowledgeMapper;
import com.dsmarket.modules.ai.provider.EmbeddingClient;
import com.dsmarket.modules.ai.search.KnowledgeSearchResult;
import com.dsmarket.modules.ai.search.RouteHit;
import com.dsmarket.modules.ai.search.RouteSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C2 双路检索编排（切片 2）：确定性、EmbeddingClient/Mapper 全 mock 不触网。
 * 覆盖：空 query；高置信双路；embedding 故障 → Dense 整路跳过仅 BM25（R5，不抛错）；
 * 两路空 → covered=false（R4）；两路都挂 → covered=false（R6）；超长 query 截 ≤200。
 */
class KnowledgeSearchServiceImplTest {

    private static final float[] VEC = {0.1f, 0.2f, 0.3f};

    private AiKnowledgeMapper mapper;
    private EmbeddingClient embed;
    private AiProperties props;
    private KnowledgeSearchServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(AiKnowledgeMapper.class);
        embed = mock(EmbeddingClient.class);
        props = new AiProperties(); // ai.search 默认：20/20/60/10/0.015
        service = new KnowledgeSearchServiceImpl(mapper, embed, props);
    }

    private AiKnowledge row(long id) {
        AiKnowledge r = new AiKnowledge();
        r.setId(id);
        r.setCategory("after_sale");
        r.setQuestion("怎么退货");
        r.setAnswer("支持7天无理由退货退款。");
        r.setFaq(true);
        r.setStatus("published");
        return r;
    }

    @Test
    void blankQuery_returnsNotCovered_noInteraction() {
        KnowledgeSearchResult r = service.search("   ");

        assertFalse(r.isCovered());
        assertTrue(r.getSegments().isEmpty());
        verifyNoInteractions(mapper, embed);
    }

    @Test
    void highConfidence_dualHit_coveredWithSegments() {
        AiKnowledge hit = row(1L);
        when(mapper.searchBm25(anyString(), anyInt())).thenReturn(List.of(hit));
        when(embed.embed(any())).thenReturn(List.of(VEC));
        when(mapper.searchDense(anyString(), anyInt())).thenReturn(List.of(hit));

        KnowledgeSearchResult r = service.search("怎么退货");

        assertTrue(r.isCovered());
        assertEquals(1, r.getSegments().size());
        assertEquals("怎么退货", r.getSegments().get(0).getQuestion());
        ArgumentCaptor<String> vecCap = ArgumentCaptor.forClass(String.class);
        verify(mapper).searchDense(vecCap.capture(), eq(20));
        assertEquals(VectorText.of(VEC), vecCap.getValue(), "查询问句向量化后喂 Dense 路");
        ArgumentCaptor<String> qCap = ArgumentCaptor.forClass(String.class);
        verify(mapper).searchBm25(qCap.capture(), eq(20));
        assertEquals("怎么退货", qCap.getValue());
    }

    @Test
    void embeddingDown_denseSkipped_stillBm25_noThrow() {
        // R5：embedding 不可用 → 整条 Dense 路跳过、仅 BM25、不抛错
        when(mapper.searchBm25(anyString(), anyInt())).thenReturn(List.of(row(1L)));
        when(embed.embed(any())).thenThrow(new BusinessException(502, "向量化服务不可用"));

        KnowledgeSearchResult r = service.search("怎么退货");

        assertTrue(r.isCovered(), "仅 BM25 高置信也应 covered");
        verify(mapper, never()).searchDense(anyString(), anyInt());
    }

    @Test
    void embeddingReturnsEmpty_denseSkipped_noThrow() {
        when(mapper.searchBm25(anyString(), anyInt())).thenReturn(List.of(row(1L)));
        when(embed.embed(any())).thenReturn(List.of()); // 上游空返回

        KnowledgeSearchResult r = service.search("怎么退货");

        assertTrue(r.isCovered());
        verify(mapper, never()).searchDense(anyString(), anyInt());
    }

    @Test
    void bothEmpty_isNotCovered_emptyToC1() {
        // R4：两路都空 → covered=false、segments/candidates 空（低置信，C1 走 FAQ 兜底）
        when(mapper.searchBm25(anyString(), anyInt())).thenReturn(List.of());
        when(embed.embed(any())).thenReturn(List.of(VEC));
        when(mapper.searchDense(anyString(), anyInt())).thenReturn(List.of());

        KnowledgeSearchResult r = service.search("完全无关的问题甲乙丙");

        assertFalse(r.isCovered());
        assertTrue(r.getSegments().isEmpty());
        assertTrue(r.getCandidates().isEmpty());
    }

    @Test
    void bothDown_isNotCovered_noThrow() {
        // R6：BM25 空 + embedding 挂（极端两路都挂）→ covered=false，不抛错
        when(mapper.searchBm25(anyString(), anyInt())).thenReturn(List.of());
        when(embed.embed(any())).thenThrow(new BusinessException(502, "向量化服务不可用"));

        KnowledgeSearchResult r = service.search("完全无关的问题甲乙丙");

        assertFalse(r.isCovered());
        assertTrue(r.getSegments().isEmpty());
    }

    @Test
    void overlongQuery_truncatedTo200_beforeBothPaths() {
        StringBuilder sb = new StringBuilder();
        sb.append("重".repeat(300));
        String longQ = sb.toString();
        when(mapper.searchBm25(anyString(), anyInt())).thenReturn(List.of());
        when(embed.embed(any())).thenReturn(List.of(VEC));
        when(mapper.searchDense(anyString(), anyInt())).thenReturn(List.of());

        service.search(longQ);

        ArgumentCaptor<String> qCap = ArgumentCaptor.forClass(String.class);
        verify(mapper).searchBm25(qCap.capture(), eq(20));
        assertEquals(200, qCap.getValue().length(), "query 防御截 ≤200（C2 §5）");
    }

    // ---- 切片 4：routes() 两路原始排名（C2 §7 效果评估数据源） ----

    @Test
    void routes_blankQuery_emptyRoutes_noInteraction() {
        RouteSearchResult r = service.routes("   ");

        assertTrue(r.getBm25().isEmpty());
        assertTrue(r.getDense().isEmpty());
        verifyNoInteractions(mapper, embed);
    }

    @Test
    void routes_returnsBothRawRankedLists_denseCarriesDistance() {
        AiKnowledge b1 = row(1L);
        AiKnowledge b2 = row(2L);
        AiKnowledge d1 = row(2L);
        d1.setDenseDistance(0.1);
        AiKnowledge d2 = row(5L);
        d2.setDenseDistance(0.4);
        when(mapper.searchBm25(anyString(), anyInt())).thenReturn(List.of(b1, b2));
        when(embed.embed(any())).thenReturn(List.of(VEC));
        when(mapper.searchDense(anyString(), anyInt())).thenReturn(List.of(d1, d2));

        RouteSearchResult r = service.routes("怎么退货");

        assertEquals(List.of(1L, 2L), r.getBm25().stream().map(RouteHit::getId).toList());
        assertEquals(1, r.getBm25().get(0).getRank());
        assertEquals(2, r.getBm25().get(1).getRank());
        assertNull(r.getBm25().get(0).getDenseDistance(), "BM25 路不带距离");
        assertEquals(List.of(2L, 5L), r.getDense().stream().map(RouteHit::getId).toList());
        assertEquals(2, r.getDense().get(1).getRank());
        assertEquals(0.1, r.getDense().get(0).getDenseDistance(), 1e-9, "Dense 路带余弦距离");
        assertEquals(0.4, r.getDense().get(1).getDenseDistance(), 1e-9);
    }

    @Test
    void routes_embeddingDown_denseEmpty_bm25StillReturned() {
        // R5 语义：embedding 挂 → Dense 整路跳过（空），BM25 仍返回，不抛错
        when(mapper.searchBm25(anyString(), anyInt())).thenReturn(List.of(row(1L)));
        when(embed.embed(any())).thenThrow(new BusinessException(502, "向量化服务不可用"));

        RouteSearchResult r = service.routes("怎么退货");

        assertEquals(1, r.getBm25().size());
        assertTrue(r.getDense().isEmpty());
        verify(mapper, never()).searchDense(anyString(), anyInt());
    }

    @Test
    void search_defaults_equalWeightAndMagnitudeOff_keepExistingSemantics() {
        // 档④默认 w=1,1 + 幅度闸默认 0=关 → 既有高置信双路语义不变（回归锚点）
        AiKnowledge hit = row(1L);
        when(mapper.searchBm25(anyString(), anyInt())).thenReturn(List.of(hit));
        when(embed.embed(any())).thenReturn(List.of(VEC));
        when(mapper.searchDense(anyString(), anyInt())).thenReturn(List.of(hit));

        KnowledgeSearchResult r = service.search("怎么退货");

        assertTrue(r.isCovered());
        assertEquals(2.0 / 61, r.getTopScore(), 1e-9, "w=1,1 → 双路 rank1 仍 2/61");
    }
}
