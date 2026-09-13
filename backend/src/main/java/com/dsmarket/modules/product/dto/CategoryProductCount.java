package com.dsmarket.modules.product.dto;

import lombok.Data;

/**
 * 分类商品计数的聚合行（Mapper 直出，REQ-20260913 §4.9）。
 *
 * <p><b>{@code cnt} 是「直挂」在该分类下的商品数，不含子分类</b>——子树求和由
 * {@code CategoryServiceImpl} 装配时递归完成。分开做是刻意的：SQL 只负责聚合，
 * 父子关系只在一处解释，避免两处各有一套"什么算后代"的口径。</p>
 */
@Data
public class CategoryProductCount {

    /** 商品直挂的分类 ID。{@code dsm_product.category_id} 为 NULL 的商品不会出现在结果里 */
    private Long categoryId;

    /** 该分类下公开可见的商品数 */
    private Long cnt;
}
