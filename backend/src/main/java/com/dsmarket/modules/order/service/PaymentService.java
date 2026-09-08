package com.dsmarket.modules.order.service;

import com.dsmarket.modules.order.dto.PaymentVO;

public interface PaymentService {

    /** Mock 支付：模拟支付成功回调，更新订单为已付款 */
    PaymentVO pay(Long userId, String orderNo, String paymentMethod);

    /** 查询支付状态 */
    PaymentVO status(Long userId, String orderNo);
}
