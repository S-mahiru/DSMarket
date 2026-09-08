package com.dsmarket.modules.ai.search;

import com.dsmarket.modules.ai.entity.AiKnowledge;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RRF 融合（C2 §3.3/F3）：等权公式、双路去重合并、单路退化、finalTop 截断、双空、排序可复现。
 * 纯逻辑无 IO，输入有序即输出确定。
 */
class RrfFuserTest {

    private static final int K = 60;

    private AiKnowledge row(long id) {
        AiKnowledge r = new AiKnowledge();
        r.setId(id);
        r.setCategory("after_sale");
        r.setQuestion("问题" + id);
        r.setAnswer("答案" + id);
        r.setStatus("published");
        return r;
    }

    @Test
    void dualTop1_sumsBothPathContributions() {
        List<AiKnowledge> bm25 = List.of(row(1));
        List<AiKnowledge> dense = List.of(row(1));

        List<FusionHit> hits = RrfFuser.fuse(bm25, dense, K, 10);

        assertEquals(1, hits.size());
        FusionHit top = hits.get(0);
        assertEquals(1, top.getBm25Rank());
        assertEquals(1, top.getDenseRank());
        assertEquals(2.0 / 61, top.getFusedScore(), 1e-9, "双路 rank1 → 1/61 + 1/61");
        assertEquals(1L, top.getSegment().getId());
    }

    @Test
    void ordering_descByFusedScore_withBothPathRanksRecorded() {
        // bm25 序 [1,2,3]；dense 序 [3,2]（id3 dense 反超）
        List<AiKnowledge> bm25 = List.of(row(1), row(2), row(3));
        List<AiKnowledge> dense = List.of(row(3), row(2));

        List<FusionHit> hits = RrfFuser.fuse(bm25, dense, K, 10);

        assertEquals(List.of(3L, 2L, 1L), hits.stream().map(h -> h.getSegment().getId()).toList(),
                "id3=1/63+1/61 > id2=2/62 > id1=1/61");

        FusionHit h3 = hits.get(0);
        assertEquals(3, h3.getBm25Rank());
        assertEquals(1, h3.getDenseRank());
        FusionHit h2 = hits.get(1);
        assertEquals(2, h2.getBm25Rank());
        assertEquals(2, h2.getDenseRank());
        FusionHit h1 = hits.get(2);
        assertEquals(1, h1.getBm25Rank());
        assertEquals(0, h1.getDenseRank(), "id1 未进 dense 路");
    }

    @Test
    void denseAbsent_degradesToSinglePathRanking() {
        List<AiKnowledge> bm25 = List.of(row(1), row(2), row(3));

        List<FusionHit> hits = RrfFuser.fuse(bm25, null, K, 10);

        assertEquals(3, hits.size());
        assertEquals(List.of(1L, 2L, 3L), hits.stream().map(h -> h.getSegment().getId()).toList(),
                "单路退化仍有效且保持原排名（F3 注）");
        assertEquals(1.0 / 61, hits.get(0).getFusedScore(), 1e-9);
        assertEquals(0, hits.get(0).getDenseRank());
    }

    @Test
    void finalTop_truncates() {
        List<AiKnowledge> bm25 = List.of(row(1), row(2), row(3));

        List<FusionHit> hits = RrfFuser.fuse(bm25, null, K, 2);

        assertEquals(2, hits.size());
        assertEquals(List.of(1L, 2L), hits.stream().map(h -> h.getSegment().getId()).toList());
    }

    @Test
    void bothAbsent_returnsEmpty() {
        assertTrue(RrfFuser.fuse(null, null, K, 10).isEmpty());
        assertTrue(RrfFuser.fuse(List.of(), List.of(), K, 10).isEmpty());
    }

    @Test
    void deterministic_repeatableOutput() {
        List<AiKnowledge> bm25 = List.of(row(1), row(2), row(5));
        List<AiKnowledge> dense = List.of(row(5), row(2), row(9));

        List<FusionHit> a = RrfFuser.fuse(bm25, dense, K, 10);
        List<FusionHit> b = RrfFuser.fuse(bm25, dense, K, 10);

        assertEquals(a.stream().map(h -> h.getSegment().getId()).toList(),
                b.stream().map(h -> h.getSegment().getId()).toList(), "同输入 → 同输出（R8 可复现）");
    }

    // ---- 切片 4：档④加权（C2 §7） ----

    @Test
    void weighted_canFlipEqualWeightOrdering() {
        // id1 bm25 rank1（无 dense）；id2 bm25 rank2 + dense rank1。
        // 等权（档③）：id2 ≈ 2/62+1/61 > id1 1/61 → id2 前；
        // w_dense=0（档④）：id2 只剩 1/62 < id1 1/61 → 序翻转，验证权重真实生效。
        List<AiKnowledge> bm25 = List.of(row(1), row(2));
        List<AiKnowledge> dense = List.of(row(2));

        List<FusionHit> eq = RrfFuser.fuse(bm25, dense, K, 10);
        assertEquals(List.of(2L, 1L), eq.stream().map(h -> h.getSegment().getId()).toList());

        List<FusionHit> w = RrfFuser.fuse(bm25, dense, K, 10, 1.0, 0.0);
        assertEquals(List.of(1L, 2L), w.stream().map(h -> h.getSegment().getId()).toList(),
                "w_dense=0 → 仅 BM25 序，等权序被翻转");
        assertEquals(1.0 / 61, w.get(0).getFusedScore(), 1e-9, "w_dense=0 → id1 只贡献 BM25 rank1");
    }

    @Test
    void weighted_uniformWeights_matchesEqualWeightBaseline() {
        // 档④ w=1,1 与档③（等权）同输出同分（收敛性）
        List<AiKnowledge> bm25 = List.of(row(1), row(2), row(3));
        List<AiKnowledge> dense = List.of(row(3), row(2));

        List<FusionHit> a = RrfFuser.fuse(bm25, dense, K, 10);
        List<FusionHit> b = RrfFuser.fuse(bm25, dense, K, 10, 1.0, 1.0);

        assertEquals(a.stream().map(h -> h.getSegment().getId()).toList(),
                b.stream().map(h -> h.getSegment().getId()).toList());
        assertEquals(a.get(0).getFusedScore(), b.get(0).getFusedScore(), 1e-9);
    }

    @Test
    void weighted_amplifyingDense_keepsDenseRank1TopAndRaisesScore() {
        // id1 bm25 rank5（深命中），dense rank1。w_dense 放大只会抬高 id1 融合分，不改变其领先。
        List<AiKnowledge> bm25 = List.of(row(2), row(3), row(4), row(5), row(1));
        List<AiKnowledge> dense = List.of(row(1));

        List<FusionHit> w1 = RrfFuser.fuse(bm25, dense, K, 10, 1.0, 1.0);
        List<FusionHit> w2 = RrfFuser.fuse(bm25, dense, K, 10, 1.0, 2.0);

        assertEquals(1L, w1.get(0).getSegment().getId());
        assertEquals(1L, w2.get(0).getSegment().getId(), "dense 权重放大不改变 dense rank1 领先");
        assertEquals(1.0 / 65 + 1.0 / 61, w1.get(0).getFusedScore(), 1e-9);
        assertEquals(1.0 / 65 + 2.0 / 61, w2.get(0).getFusedScore(), 1e-9,
                "score = Σ w_r/(K+rank)：w_dense 线性放大 Dense 贡献");
    }

    @Test
    void denseDistance_carriedFromDenseRow_ontoFusionHit() {
        AiKnowledge r1 = row(1);
        r1.setDenseDistance(0.2); // Dense 路出列回填
        AiKnowledge r2 = row(2);  // 仅 BM25
        List<AiKnowledge> bm25 = List.of(r1, r2);
        List<AiKnowledge> dense = List.of(r1);

        List<FusionHit> hits = RrfFuser.fuse(bm25, dense, K, 10);

        FusionHit h1 = hits.stream().filter(h -> h.getSegment().getId() == 1L).findFirst().orElseThrow();
        assertEquals(0.2, h1.getDenseDistance(), 1e-9, "dr>0 → FusionHit 回填 Dense 距离");
        FusionHit h2 = hits.stream().filter(h -> h.getSegment().getId() == 2L).findFirst().orElseThrow();
        assertEquals(null, h2.getDenseDistance(), "dr=0 → 距离为 null");
    }
}
