package com.dsmarket.modules.product.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.dsmarket.common.config.JsonbTypeHandler;
import com.dsmarket.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dsm_product")
public class Product extends BaseEntity {

    private String name;
    private String title;
    private String brief;
    private String description;
    private Long categoryId;
    private String brand;
    private String unit;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private Integer stock;
    private Integer sales;
    private String mainImage;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String subImages;      // JSONB
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String detailImages;   // JSONB
    private Integer hasSku;
    private Integer isFeatured;
    private Integer status;
    private BigDecimal weight;
    private Integer sortOrder;
}
