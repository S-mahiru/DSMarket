package com.dsmarket.modules.product.dto;

import com.dsmarket.modules.product.entity.Product;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductListVO {

    private Long id;
    private String name;
    private String brief;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private String mainImage;
    private Integer stock;
    private Integer sales;
    private String unit;
    private Integer status;

    public static ProductListVO from(Product p) {
        ProductListVO vo = new ProductListVO();
        vo.setId(p.getId());
        vo.setName(p.getName());
        vo.setBrief(p.getBrief());
        vo.setPrice(p.getPrice());
        vo.setOriginalPrice(p.getOriginalPrice());
        vo.setMainImage(p.getMainImage());
        vo.setStock(p.getStock());
        vo.setSales(p.getSales());
        vo.setUnit(p.getUnit());
        vo.setStatus(p.getStatus());
        return vo;
    }
}
