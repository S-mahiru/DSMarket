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
@TableName("dsm_product_sku")
public class ProductSku extends BaseEntity {

    private Long productId;
    private String skuCode;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private Integer stock;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String specs;   // JSONB
    private String image;
    private BigDecimal weight;
    private Integer sortOrder;
    private Integer status;
}
