package com.dsmarket.modules.ai.service.impl;

import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.util.VectorText;
import com.dsmarket.modules.ai.config.AiProperties;
import com.dsmarket.modules.ai.entity.AiKnowledge;
import com.dsmarket.modules.ai.mapper.AiKnowledgeMapper;
import com.dsmarket.modules.ai.provider.EmbeddingClient;
import com.dsmarket.modules.ai.search.ConfidenceGate;
import com.dsmarket.modules.ai.search.FusionHit;
import com.dsmarket.modules.ai.search.KnowledgeOrganizer;
import com.dsmarket.modules.ai.search.KnowledgeSearchResult;
import com.dsmarket.modules.ai.search.RouteHit;
import com.dsmarket.modules.ai.search.RouteSearchResult;
import com.dsmarket.modules.ai.search.RrfFuser;
import com.dsmarket.modules.ai.service.KnowledgeSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * C2 双路检索引擎实现（切片 2 + 切片 4 加权/幅度闸/routes）。
 *
 * <p>编排：BM25 路 → Dense 路（query 向量化，embedding 故障/超时 → 整路跳过仅 BM25，R5）
 * → RRF 融合（档④加权，w 读配置）取 top FINAL_TOP → F4 置信闸（含幅度闸 #3，dense-min-sim 读配置）
 * → F6 片段组织。全程<b>不向上抛错</b>，低置信/冲突/双空一律落 {@code covered=false}
 * （空 segments），供 C1 走 FAQ 兜底。{@link #routes} 暴露两路原始排名供 §7 效果评估。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeSearchServiceImpl implements KnowledgeSearchService {

    /** 检索 query 防御上限（C2 §5 ≤200）。 */
    private static final int MAX_QUERY_LEN = 200;

    private final AiKnowledgeMapper knowledgeMapper;
    private final EmbeddingClient embeddingClient;
    private final AiProperties properties;

    @Override
    public KnowledgeSearchResult search(String query) {
        KnowledgeSearchResult result = new KnowledgeSearchResult();
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            result.setQuery("");
            return result; // covered=false、segments 空（语义 = 空 top-k）
        }
        String q = trimmed.length() > MAX_QUERY_LEN ? trimmed.substring(0, MAX_QUERY_LEN) : trimmed;
        result.setQuery(q);

        AiProperties.Search s = properties.getSearch();
        List<AiKnowledge> bm25 = bm25Search(q, s.getTopkBm25());
        List<AiKnowledge> dense = denseSearch(q, s.getTopkDense());

        // 档④加权融合（w 读配置，默认 1.0=等权档③）+ 置信闸（含幅度闸 #3）
        List<FusionHit> fused = RrfFuser.fuse(bm25, dense, s.getRrfK(), s.getFinalTop(),
                s.getWBm25(), s.getWDense());
        boolean gatePass = ConfidenceGate.isHighConfidence(fused, s.getConfMin(), s.getDenseMinSim());
        KnowledgeOrganizer.Outcome outcome = KnowledgeOrganizer.organize(fused);

        // covered = 闸过 且 F6 组织无冲突；只有 covered 才带片段（低置信/冲突 → 空 top-k 语义）
        boolean covered = gatePass && outcome.isCovered();
        result.setCovered(covered);
        result.setSegments(covered ? outcome.getSegments() : List.of());
        result.setTopScore(fused.isEmpty() ? 0 : fused.get(0).getFusedScore());
        result.setCandidates(fused);
        return result;
    }

    @Override
    public RouteSearchResult routes(String query) {
        RouteSearchResult out = new RouteSearchResult();
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            out.setQuery("");
            return out; // 双路空
        }
        String q = trimmed.length() > MAX_QUERY_LEN ? trimmed.substring(0, MAX_QUERY_LEN) : trimmed;
        out.setQuery(q);

        AiProperties.Search s = properties.getSearch();
        out.setBm25(toRouteHits(bm25Search(q, s.getTopkBm25())));
        out.setDense(toRouteHits(denseSearch(q, s.getTopkDense())));
        return out;
    }

    /** 有序行 → RouteHit 列表（rank=下标+1；denseDistance 仅 Dense 行经 searchDense 出列回填，非 Dense 恒 null）。 */
    private List<RouteHit> toRouteHits(List<AiKnowledge> rows) {
        List<RouteHit> hits = new ArrayList<>();
        List<AiKnowledge> list = rows == null ? List.of() : rows;
        for (int i = 0; i < list.size(); i++) {
            AiKnowledge row = list.get(i);
            RouteHit hit = new RouteHit();
            hit.setId(row.getId());
            hit.setRank(i + 1);
            hit.setDenseDistance(row.getDenseDistance());
            hits.add(hit);
        }
        return hits;
    }

    /** BM25 路：无索引/分词异常 → 捕获降级为空列表（F1 异常列），不阻断。 */
    private List<AiKnowledge> bm25Search(String q, int topK) {
        try {
            return knowledgeMapper.searchBm25(q, topK);
        } catch (RuntimeException e) {
            log.warn("[ai] BM25 检索降级为空. q={}", q, e);
            return List.of();
        }
    }

    /** Dense 路：embedding 不可用/超时/上游空 → 整路跳过返回空（仅 BM25，R5），不抛错。 */
    private List<AiKnowledge> denseSearch(String q, int topK) {
        try {
            List<float[]> vectors = embeddingClient.embed(List.of(q));
            if (vectors == null || vectors.isEmpty()) {
                log.warn("[ai] embedding 返回空 → Dense 整路跳过. q={}", q);
                return List.of();
            }
            return knowledgeMapper.searchDense(VectorText.of(vectors.get(0)), topK);
        } catch (BusinessException e) {
            log.warn("[ai] embedding 服务不可用({}) → Dense 整路跳过，仅 BM25（R5）. q={}", e.getCode(), q);
            return List.of();
        } catch (RuntimeException e) {
            log.warn("[ai] Dense 检索降级为空. q={}", q, e);
            return List.of();
        }
    }
}
