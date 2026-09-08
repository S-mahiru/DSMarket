package com.dsmarket.modules.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderListVO {

    private String orderNo;
    private BigDecimal totalAmount;
    private BigDecimal shippingFee;
    private BigDecimal actualAmount;
    private Integer status;
    private String statusName;
    private Integer itemCount;
    private List<OrderItemVO> orderItems;
    private LocalDateTime createdAt;
}
