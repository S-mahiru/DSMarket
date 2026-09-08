package com.dsmarket.modules.ai.search;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索结果（C2-F3/F4/F6 出口）。
 *
 * <p>{@code covered=false} 表示"低置信/未覆盖"，语义上等价给 C1 的<b>空 top-k</b>
 * （C1 走 FAQ 兜底，见 REQ §3.4/§6 R4/R6/R9）——不设独立"未覆盖"态。
 * {@code segments} 为 F6 组织后真正喂模型/输出的片段（锚定组 ≤3，同一主题仅一条口径），
 * 仅在 covered=true 时非空。{@code candidates} 为融合 top-N 候选（调试/测试可见，不进 LLM）。</p>
 */
@Data
public class KnowledgeSearchResult {

    /** 实际用于检索的 query（trim + 防御截 ≤200） */
    private String query;
    /** true=高置信（F4 闸过且 F6 无冲突）；false=低置信/冲突/双路空 → segments 空 */
    private boolean covered;
    /** F6 组织后喂模型的片段（≤3，同主题单一口径） */
    private List<KnowledgeSegment> segments = new ArrayList<>();
    /** 融合 top1 得分（闸用；测试断言可复现） */
    private double topScore;
    /** 融合 top-N 候选（含每路 rank，调试/测试可见） */
    private List<FusionHit> candidates = new ArrayList<>();
}
