package com.dsmarket.modules.ai.search;

import lombok.Data;

/**
 * RRF 融合后的单个候选（C2-F3/F4/F6 共用）。
 *
 * <p>保留每路 1-based 排名供置信闸判断 top1 是否在某路 top5 内有席位；rank=0 表示该路缺席
 * （未命中，或 embedding 降级整路缺席）。fusedScore 只作排序与阈值，不随片段喂给模型。</p>
 */
@Data
public class FusionHit {

    private KnowledgeSegment segment;
    private double fusedScore;
    /** BM25 路 1-based 排名；0 = 该路未命中/缺席 */
    private int bm25Rank;
    /** Dense 路 1-based 排名；0 = 该路未命中/缺席（含 embedding 降级） */
    private int denseRank;
    /** Dense 余弦距离（pgvector {@code <=>}）；仅 denseRank>0 时非空，幅度闸 #3 数据源（0/空=不判） */
    private Double denseDistance;
}
