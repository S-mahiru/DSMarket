package com.dsmarket.modules.product.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class ProductDetailVO {

    private Long id;
    private String name;
    private String title;
    private String brief;
    private String description;
    private String mainImage;
    private List<String> subImages = new ArrayList<>();
    private List<String> detailImages = new ArrayList<>();
    private Long categoryId;
    private String categoryName;
    private String brand;
    private String unit;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private Integer stock;
    private Integer sales;
    private Integer hasSku;
    private Integer isFeatured;
    private Integer status;

    /**
     * 所属店铺 ID（REQ-20260913 §4.5）。<b>为 null 表示平台自营</b> —— 前端据此渲染
     * 「平台自营」并链到自营专区，而不是渲染一个空的商家行。
     */
    private Long shopId;

    /** 所属店铺名；自营商品为 null */
    private String shopName;

    private List<SpecDim> specDims = new ArrayList<>();
    private List<ProductSkuVO> skus = new ArrayList<>();
}
