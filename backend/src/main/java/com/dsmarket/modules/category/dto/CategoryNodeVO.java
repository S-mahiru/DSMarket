package com.dsmarket.modules.category.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CategoryNodeVO {

    private Long id;
    private String name;
    private Integer level;

    /**
     * 该分类**及其整棵子树**下公开可见的商品数（REQ-20260913 §4.9）。
     *
     * <p>口径与列表页 {@code ?categoryId=} 的筛选结果**恒等**：徽标标 3，点进去就必须是 3 条。
     * 装配方式见 {@code CategoryServiceImpl#applyProductCounts}（**必须整表递归**，理由在那里）。</p>
     */
    private Long productCount;

    private List<CategoryNodeVO> children = new ArrayList<>();
}
