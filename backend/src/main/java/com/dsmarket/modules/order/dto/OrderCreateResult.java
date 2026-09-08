package com.dsmarket.modules.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class OrderCreateResult {

    private String orderNo;
    private BigDecimal actualAmount;
    private Integer status;
}
