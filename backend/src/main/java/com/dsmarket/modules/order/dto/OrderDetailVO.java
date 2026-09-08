package com.dsmarket.modules.order.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderDetailVO {

    private String orderNo;
    private BigDecimal totalAmount;
    private BigDecimal shippingFee;
    private BigDecimal discountAmount;
    private BigDecimal actualAmount;
    private Integer status;
    private String statusName;
    private String remark;
    private String paymentMethod;
    private AddressSnapshot address;
    private List<OrderItemVO> orderItems;
    private List<TimelineItem> timeline;
    private LocalDateTime createdAt;
}
