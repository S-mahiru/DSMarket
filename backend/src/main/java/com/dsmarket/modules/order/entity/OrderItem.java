package com.dsmarket.modules.order.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.dsmarket.common.config.JsonbTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单项表（仅追加，无 updated_at / deleted 列）
 */
@Data
@TableName("dsm_order_item")
public class OrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long productId;
    private Long skuId;
    private String productName;
    private String productImage;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String skuSpecs;      // JSONB 规格快照
    private BigDecimal unitPrice;
    private Integer quantity;
    private BigDecimal subtotal;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
