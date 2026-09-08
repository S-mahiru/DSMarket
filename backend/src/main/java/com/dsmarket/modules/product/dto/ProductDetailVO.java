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
    private List<SpecDim> specDims = new ArrayList<>();
    private List<ProductSkuVO> skus = new ArrayList<>();
}
