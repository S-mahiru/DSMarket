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
    /**
     * 归属店铺ID。**`null` = 平台自营**（ADMIN 在后台建的商品），见 REQ-20260912 §4.3。
     *
     * <p>隔离判据。两条硬规则（§4.4）：① 本字段**绝不来自请求参数** —— 一律由服务端从
     * {@code SecurityContext} → {@code dsm_shop} 解析后赋值；② 商家侧 by-id 操作必须先经
     * {@code requireOwnedProduct} 校验归属，不复用无校验的 admin 方法。
     */
    private Long shopId;
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
