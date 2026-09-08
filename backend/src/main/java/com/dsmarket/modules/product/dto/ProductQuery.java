package com.dsmarket.modules.product.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductQuery {

    private String keyword;
    private Long categoryId;
    private String brand;
    private BigDecimal minPrice;
    private BigDecimal maxPrice;
    private String sortBy;
    private String sortOrder;
}
