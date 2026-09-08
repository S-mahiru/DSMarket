package com.dsmarket.modules.ai.search;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 双路原始排名（C2 §7 效果评估数据源，dev 端点 /dev/knowledge-routes 返回）。
 *
 * <p>区别于 {@link KnowledgeSearchResult}（已 RRF 融合 + 置信闸 + F6 组织的喂模型结果），
 * 本结果<b>只暴露两路各自的有序 top-k</b>（不含融合/闸/组织），供评估装置离线重算四档
 * （①BM25 ②Dense ③RRF 等权 ④RRF 加权）与幅度闸阈值标定；生产路径不调用。
 * 每路列表顺序即排名（rank = 下标+1）。</p>
 */
@Data
public class RouteSearchResult {

    /** 实际用于检索的 query（trim + 防御截 ≤200） */
    private String query;
    /** BM25 路有序 top-k（含 id/rank；denseDistance 恒 null） */
    private List<RouteHit> bm25 = new ArrayList<>();
    /** Dense 路有序 top-k（含 id/rank/余弦距离） */
    private List<RouteHit> dense = new ArrayList<>();
}
