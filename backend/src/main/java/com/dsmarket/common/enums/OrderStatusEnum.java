package com.dsmarket.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OrderStatusEnum {

    PENDING_PAYMENT(0, "待付款"),
    PAID(1, "已付款"),
    SHIPPED(2, "已发货"),
    RECEIVED(3, "已收货"),
    COMPLETED(4, "已完成"),
    CANCELLED(5, "已取消"),
    CLOSED(6, "已关闭");

    private final int value;
    private final String displayName;

    public static OrderStatusEnum fromValue(int value) {
        for (OrderStatusEnum status : values()) {
            if (status.value == value) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知订单状态: " + value);
    }
}
