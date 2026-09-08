package com.dsmarket.modules.ai.search;

import com.dsmarket.modules.ai.entity.AiKnowledge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）融合 —— 纯静态、确定性（C2 §3.3/F3，档④加权见 §7）。
 *
 * <p>公式（加权变体，档④）：{@code score(item) = Σ_{r∈出现路} w_r / (RRF_K + rank_r)}，
 * rank 从 1 计；等权基线（档③）即 w_r=1，与旧公式一致。仅出现在任一 top-k 内的条目有分。
 * 某路缺席（embedding 降级或双空）→ 退化为单路排名，仍有效（F3 注）。</p>
 *
 * <p>输入两路须各自已按相关度<b>有序</b>（mapper 返回顺序即排名）；融合结果按分降序。
 * 同分时依赖 {@code List.sort} 稳定排序 + 双路唯一并入次序，输出确定可复现（R8）。
 * Dense 行经 {@code searchDense} 出列余弦距离（实体瞬时 denseDistance），dr&gt;0 时回填
 * {@link FusionHit#setDenseDistance(Double)} 供幅度闸 #3 使用。</p>
 */
public final class RrfFuser {

    private RrfFuser() {
    }

    /**
     * 融合两路有序命中，返回按 fusedScore 降序的前 finalTop 条（等权基线 w=1，档③）。
     *
     * @param bm25     BM25 路有序行（可空）
     * @param dense    Dense 路有序行（可空 = embedding 降级缺席）
     * @param rrfK     RRF 常数（ai.search.rrf-k）
     * @param finalTop 融合后截取条数（ai.search.final-top）
     */
    public static List<FusionHit> fuse(List<AiKnowledge> bm25, List<AiKnowledge> dense, int rrfK, int finalTop) {
        return fuse(bm25, dense, rrfK, finalTop, 1.0, 1.0);
    }

    /**
     * 加权融合（档④，C2 §7）：{@code score = w_bm25/(K+rank_b) + w_dense/(K+rank_d)}。
     *
     * @param wBm25  BM25 路权重（ai.search.weight-bm25；1.0=等权）
     * @param wDense Dense 路权重（ai.search.weight-dense；1.0=等权）
     */
    public static List<FusionHit> fuse(List<AiKnowledge> bm25, List<AiKnowledge> dense, int rrfK, int finalTop,
                                       double wBm25, double wDense) {
        Map<Long, Integer> bm25Rank = rankIndexOf(bm25);
        Map<Long, Integer> denseRank = rankIndexOf(dense);
        Map<Long, Double> denseDist = denseDistanceOf(dense);

        List<FusionHit> hits = new ArrayList<>();
        Map<Long, FusionHit> byId = new HashMap<>();
        // 双路唯一并入：先 BM25 序、再 Dense 新增序（并入次序确定 → 稳定排序结果确定）
        for (AiKnowledge row : orEmpty(bm25)) {
            addIfAbsent(hits, byId, row, rrfK, wBm25, wDense, bm25Rank, denseRank, denseDist);
        }
        for (AiKnowledge row : orEmpty(dense)) {
            addIfAbsent(hits, byId, row, rrfK, wBm25, wDense, bm25Rank, denseRank, denseDist);
        }

        hits.sort((a, b) -> Double.compare(b.getFusedScore(), a.getFusedScore()));
        return hits.size() <= finalTop ? hits : new ArrayList<>(hits.subList(0, finalTop));
    }

    private static void addIfAbsent(List<FusionHit> hits, Map<Long, FusionHit> byId,
                                    AiKnowledge row, int rrfK, double wBm25, double wDense,
                                    Map<Long, Integer> bm25Rank, Map<Long, Integer> denseRank,
                                    Map<Long, Double> denseDist) {
        if (byId.containsKey(row.getId())) {
            return;
        }
        FusionHit hit = new FusionHit();
        hit.setSegment(KnowledgeSegment.of(row));
        int br = rankOrZero(bm25Rank, row.getId());
        int dr = rankOrZero(denseRank, row.getId());
        hit.setBm25Rank(br);
        hit.setDenseRank(dr);
        hit.setFusedScore(score(br, dr, rrfK, wBm25, wDense));
        if (dr > 0) {
            hit.setDenseDistance(denseDist.get(row.getId()));
        }
        byId.put(row.getId(), hit);
        hits.add(hit);
    }

    private static double score(int bm25Rank, int denseRank, int rrfK, double wBm25, double wDense) {
        double s = 0;
        if (bm25Rank > 0) {
            s += wBm25 / (rrfK + bm25Rank);
        }
        if (denseRank > 0) {
            s += wDense / (rrfK + denseRank);
        }
        return s;
    }

    /** 1-based 排名映射；行序即排名（第一行 rank=1）。 */
    private static Map<Long, Integer> rankIndexOf(List<AiKnowledge> rows) {
        Map<Long, Integer> map = new HashMap<>();
        List<AiKnowledge> list = orEmpty(rows);
        for (int i = 0; i < list.size(); i++) {
            map.putIfAbsent(list.get(i).getId(), i + 1);
        }
        return map;
    }

    /** Dense 行 id → 余弦距离（searchDense 出列 dense_distance 回填；行序上按需首个覆盖同 id）。 */
    private static Map<Long, Double> denseDistanceOf(List<AiKnowledge> rows) {
        Map<Long, Double> map = new HashMap<>();
        for (AiKnowledge row : orEmpty(rows)) {
            if (row.getId() != null && row.getDenseDistance() != null) {
                map.putIfAbsent(row.getId(), row.getDenseDistance());
            }
        }
        return map;
    }

    private static int rankOrZero(Map<Long, Integer> rank, Long id) {
        Integer r = rank.get(id);
        return r == null ? 0 : r;
    }

    private static List<AiKnowledge> orEmpty(List<AiKnowledge> list) {
        return list == null ? List.of() : list;
    }
}
