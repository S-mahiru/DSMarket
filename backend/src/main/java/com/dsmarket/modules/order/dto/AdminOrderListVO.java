package com.dsmarket.modules.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 管理端订单列表 VO */
@Data
public class AdminOrderListVO {

    private String orderNo;
    private String buyerName;
    private BigDecimal totalAmount;
    private BigDecimal shippingFee;
    private BigDecimal actualAmount;
    private Integer status;
    private String statusName;
    private Integer itemCount;
    private LocalDateTime paymentTime;
    private LocalDateTime deliveryTime;
    private LocalDateTime createdAt;
}
