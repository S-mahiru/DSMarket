package com.dsmarket.modules.order.dto;

import lombok.Data;

@Data
public class CreateOrderRequest {

    private Long addressId;
    private String remark;
}
