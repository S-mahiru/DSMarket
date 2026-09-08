package com.dsmarket.modules.order.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.dsmarket.common.config.JsonbTypeHandler;
import com.dsmarket.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单主表（软删除）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dsm_order_info")
public class OrderInfo extends BaseEntity {

    private String orderNo;
    private Long userId;
    private BigDecimal totalAmount;
    private BigDecimal shippingFee;
    private BigDecimal discountAmount;
    private BigDecimal actualAmount;
    private BigDecimal paymentAmount;
    private Integer status;
    private String remark;
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String addressSnapshot;   // JSONB 地址快照
    private String paymentMethod;
    private LocalDateTime paymentTime;
    private LocalDateTime deliveryTime;
    private LocalDateTime receiveTime;
    private LocalDateTime cancelTime;
    private String cancelReason;
    private LocalDateTime closeTime;
}
