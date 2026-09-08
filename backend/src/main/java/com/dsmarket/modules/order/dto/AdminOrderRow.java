package com.dsmarket.modules.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 管理端订单列表联查行（订单表 LEFT JOIN 用户表取买家名） */
@Data
public class AdminOrderRow {

    private Long id;
    private String orderNo;
    private Long userId;
    private String buyerName;
    private BigDecimal totalAmount;
    private BigDecimal shippingFee;
    private BigDecimal actualAmount;
    private BigDecimal paymentAmount;
    private Integer status;
    private LocalDateTime paymentTime;
    private LocalDateTime deliveryTime;
    private LocalDateTime createdAt;
}
