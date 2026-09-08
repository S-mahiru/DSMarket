package com.dsmarket.modules.ai.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F4 置信闸（C2 §3.4）：决定性双条件（score&lt;CONF_MIN 或 top1 未进任一路 top5 → 低置信），可复现。
 * 注：闸只消费 top1 的 fusedScore 与每路 rank，与分数如何由 RRF 得出无关，故可任意构造数值做单测。
 */
class ConfidenceGateTest {

    private static final double CONF_MIN = 0.015;

    private FusionHit hit(double score, int bm25Rank, int denseRank) {
        FusionHit h = new FusionHit();
        h.setFusedScore(score);
        h.setBm25Rank(bm25Rank);
        h.setDenseRank(denseRank);
        return h;
    }

    /** 3-arg gate 用：带 Dense 余弦距离（相似度 = 1 - 距离）。 */
    private FusionHit hit(double score, int bm25Rank, int denseRank, Double denseDistance) {
        FusionHit h = hit(score, bm25Rank, denseRank);
        h.setDenseDistance(denseDistance);
        return h;
    }

    @Test
    void emptyCandidate_isLowConfidence() {
        assertFalse(ConfidenceGate.isHighConfidence(List.of(), CONF_MIN));
        assertFalse(ConfidenceGate.isHighConfidence(null, CONF_MIN));
    }

    @Test
    void scoreBelowConfMin_isLowEvenInTop1OfBothPaths() {
        // 双路 rank1 正常分数 2/61≈0.0327，此处人为压到阈值下验证第一闸
        assertFalse(ConfidenceGate.isHighConfidence(List.of(hit(0.01, 1, 1)), CONF_MIN));
    }

    @Test
    void scoreAboveButNotInAnyTop5_isLow() {
        // 第二闸：单路 deep 命中（如仅 dense rank6，1/66≈0.0152 恰过阈值）不被采信
        assertFalse(ConfidenceGate.isHighConfidence(List.of(hit(0.05, 6, 6)), CONF_MIN));
        assertFalse(ConfidenceGate.isHighConfidence(List.of(hit(0.05, 6, 0)), CONF_MIN));
        // 无任何路席位（极端，正常不会发生）也判低
        assertFalse(ConfidenceGate.isHighConfidence(List.of(hit(0.05, 0, 0)), CONF_MIN));
    }

    @Test
    void presentInTop5OfEitherPath_isHigh() {
        assertTrue(ConfidenceGate.isHighConfidence(List.of(hit(0.05, 1, 0)), CONF_MIN));
        assertTrue(ConfidenceGate.isHighConfidence(List.of(hit(0.05, 0, 3)), CONF_MIN));
        assertTrue(ConfidenceGate.isHighConfidence(List.of(hit(0.05, 2, 6)), CONF_MIN));
    }

    @Test
    void scoreExactlyConfMin_passesFirstGate_whenInTop5() {
        assertTrue(ConfidenceGate.isHighConfidence(List.of(hit(CONF_MIN, 1, 0)), CONF_MIN),
                "判定是 score<CONF_MIN 才低置信；等于阈值不触发");
    }

    // ---- 切片 4：相似度幅度闸 #3（闭合 DECISION-20260908-C2 §2 gap） ----

    @Test
    void magnitude_nearDense_passWhenSimAboveThreshold() {
        // top1 双路 rank1，dist 0.2 → sim 0.8 ≥ dense-min-sim 0.7 → 高置信
        assertTrue(ConfidenceGate.isHighConfidence(List.of(hit(0.05, 1, 1, 0.2)), CONF_MIN, 0.7));
    }

    @Test
    void magnitude_farDense_isLowConfidence() {
        // 无关问句被最近邻过闸的修复：dist 0.8 → sim 0.2 < 0.7 → 低置信
        assertFalse(ConfidenceGate.isHighConfidence(List.of(hit(0.05, 1, 1, 0.8)), CONF_MIN, 0.7));
    }

    @Test
    void magnitude_simExactlyThreshold_passes() {
        // 判定是 sim < min 才低置信；等于阈值不触发
        assertTrue(ConfidenceGate.isHighConfidence(List.of(hit(0.05, 1, 1, 0.3)), CONF_MIN, 0.7));
    }

    @Test
    void magnitude_disabledByDefault_zeroMinNeverFires() {
        // 默认 dense-min-sim=0：任何非负相似度都过 → 标定前无行为变化（向后兼容）
        assertTrue(ConfidenceGate.isHighConfidence(List.of(hit(0.05, 1, 1, 0.9)), CONF_MIN, 0.0));
    }

    @Test
    void magnitude_denseRankZero_notJudged_pureBm25Survives() {
        // dr=0（dense 未命中/embedding 降级）→ 幅度闸不判，纯 BM25 top1 保持高置信（R5 语义保护）
        assertTrue(ConfidenceGate.isHighConfidence(List.of(hit(0.05, 1, 0, null)), CONF_MIN, 0.7));
    }

    @Test
    void magnitude_denseBelowTop5_alreadyLowByRankRule() {
        // dr=6 不满足任一 top5 席位 → 第 2 闸先拦（幅度无需参与）
        assertFalse(ConfidenceGate.isHighConfidence(List.of(hit(0.05, 6, 6, 0.2)), CONF_MIN, 0.7));
    }
}
