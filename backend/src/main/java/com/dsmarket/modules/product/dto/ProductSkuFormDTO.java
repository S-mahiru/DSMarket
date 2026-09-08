package com.dsmarket.modules.product.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ProductSkuFormDTO {

    private Long id;
    private String skuCode;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private Integer stock;
    private List<SpecItem> specs;
    private String image;
    private BigDecimal weight;
    private Integer sortOrder;
    private Integer status;
}
