package com.dsmarket.modules.ai.search;

import java.util.List;

/**
 * F4 置信闸 —— 纯静态、确定性（C2 §3.4，含切片 4 幅度闸 #3）。
 *
 * <p>低置信 → 检索返回空 top-k 给 C1（走 FAQ 兜底）。决定性规则（DECISION-20260908-C2-检索引擎）：
 * <ol>
 *   <li>top1 融合分 &lt; CONF_MIN（初值 0.015）→ 低置信；</li>
 *   <li>top1 未进入任一<b>存在</b>的路的 top5（单路深度命中不被采信）→ 低置信；
 *       这一闸使"仅在 rank6 单路命中"这类恰过阈值的弱命中（1/66≈0.0152）也被拦下；</li>
 *   <li>相似度幅度闸（#3，闭合 DECISION-20260908-C2 §2 gap）：top1 在 Dense 路有席位(dr&gt;0)时，
 *       其余弦相似度(1 - pgvector 余弦距离)低于 dense-min-sim → 无关问句/低置信；
 *       dr=0（该路未命中/embedding 降级）不判 → 纯 BM25 命中不被误杀（R5）；dense-min-sim=0 恒不过（默认关闭）。</li>
 * </ol>
 * 两路都缺席/全空 → 同样低置信（R4/R6）。同 query+同数据 → 同判定（R8 可复现）。</p>
 */
public final class ConfidenceGate {

    private ConfidenceGate() {
    }

    /**
     * @param fused   融合后<b>已按分降序</b>的候选列表（RrfFuser 输出）
     * @param confMin CONF_MIN（ai.search.conf-min）
     * @return true=高置信（闸过）；false=低置信
     */
    public static boolean isHighConfidence(List<FusionHit> fused, double confMin) {
        return isHighConfidence(fused, confMin, 0.0);
    }

    /**
     * @param fused       融合后<b>已按分降序</b>的候选列表（RrfFuser 输出）
     * @param confMin     CONF_MIN（ai.search.conf-min）
     * @param denseMinSim 幅度闸阈值（ai.search.dense-min-sim；top1 Dense 余弦相似度低于它 → 低置信；0=关闭）
     * @return true=高置信（闸过）；false=低置信
     */
    public static boolean isHighConfidence(List<FusionHit> fused, double confMin, double denseMinSim) {
        if (fused == null || fused.isEmpty()) {
            return false;
        }
        FusionHit top = fused.get(0);
        if (top.getFusedScore() < confMin) {
            return false;
        }
        int br = top.getBm25Rank();
        int dr = top.getDenseRank();
        boolean inTop5EitherPath = (br > 0 && br <= 5) || (dr > 0 && dr <= 5);
        if (!inTop5EitherPath) {
            return false;
        }
        if (denseMinSim > 0 && dr > 0 && top.getDenseDistance() != null) {
            double sim = 1.0 - top.getDenseDistance();
            if (sim < denseMinSim) {
                return false;
            }
        }
        return true;
    }
}
