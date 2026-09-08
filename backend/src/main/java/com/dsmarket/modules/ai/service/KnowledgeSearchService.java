package com.dsmarket.modules.ai.service;

import com.dsmarket.modules.ai.search.KnowledgeSearchResult;
import com.dsmarket.modules.ai.search.RouteSearchResult;

/**
 * C2 双路检索引擎（F1 BM25 + F2 Dense → F3 RRF → F4 置信闸 → F6 片段组织）。
 *
 * <p>对上层（C1 search_knowledge 工具，切片 3 接入）的唯一出口：{@link #search} <b>永不抛错</b>，
 * 结果以 {@code covered} 表达"高置信有片段" vs "低置信/冲突/双路空 → 空给 C1 走 FAQ 兜底"。
 * embedding 服务不可用 → Dense 整路跳过、仅 BM25（R5），不抛错。{@link #routes} 供 C2 §7
 * 效果评估（dev 端点），只暴露两路原始排名，不参与生产路径。</p>
 */
public interface KnowledgeSearchService {

    /**
     * 检索知识库并组织成喂模型的片段。
     *
     * @param query 用户问句（内部 trim、截 ≤200）
     * @return covered=false → segments 恒空；covered=true → segments ≤3（同主题单一口径）
     */
    KnowledgeSearchResult search(String query);

    /**
     * 双路原始排名（C2 §7 效果评估数据源）：不融合、不过闸、不组织，只回两路各自有序 top-k
     * （Dense 路含余弦距离）。同 {@link #search}：embedding 不可用 → Dense 路为空、不抛错；
     * blank query → 双路空。
     *
     * <p>为保持接口可作函数式类型（单抽象方法 {@link #search}；测试 stub 用 lambda 只覆 search），
     * 本方法带默认实现<b>不做事</b>；真实实现（{@code KnowledgeSearchServiceImpl}）覆写接 DB/embedding。
     * 生产路径唯一注入实现即 impl，默认实现不会被调用；误用默认实现会抛明确异常，避免静默空结果。</p>
     *
     * @param query 用户问句（内部 trim、截 ≤200）
     * @return 两路原始排名（列表顺序即 rank）
     */
    default RouteSearchResult routes(String query) {
        throw new UnsupportedOperationException("routes() 仅供 KnowledgeSearchServiceImpl 覆写实现，stub 未提供");
    }
}
