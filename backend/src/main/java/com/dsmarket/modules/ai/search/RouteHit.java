package com.dsmarket.modules.ai.search;

import lombok.Data;

/**
 * 单路原始命中（评估/调试用，见 {@link RouteSearchResult}）：id + 1-based 排名。
 *
 * <p>rank 即该路列表位置（第一行 rank=1）。{@code denseDistance} 仅 Dense 路非空
 * （pgvector 余弦距离；相似度 = 1 - 距离），供幅度闸阈值标定/评估离线分析。</p>
 */
@Data
public class RouteHit {

    private Long id;
    /** 该路 1-based 排名（列表顺序即排名） */
    private int rank;
    /** Dense 余弦距离；仅 Dense 路命中时非空，其余为 null */
    private Double denseDistance;
}
