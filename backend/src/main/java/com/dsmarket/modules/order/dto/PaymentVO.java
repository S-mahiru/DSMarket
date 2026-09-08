package com.dsmarket.modules.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class PaymentVO {

    private String paymentNo;
    private Integer status;
    private LocalDateTime payTime;
    private BigDecimal amount;
}
