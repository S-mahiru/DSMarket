package com.dsmarket.modules.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsmarket.common.enums.OrderStatusEnum;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.order.dto.PaymentVO;
import com.dsmarket.modules.order.entity.OrderInfo;
import com.dsmarket.modules.order.entity.OrderItem;
import com.dsmarket.modules.order.entity.Payment;
import com.dsmarket.modules.order.mapper.OrderInfoMapper;
import com.dsmarket.modules.order.mapper.OrderItemMapper;
import com.dsmarket.modules.order.mapper.PaymentMapper;
import com.dsmarket.modules.order.service.PaymentService;
import com.dsmarket.modules.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private static final DateTimeFormatter PAY_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OrderInfoMapper orderInfoMapper;
    private final OrderItemMapper orderItemMapper;
    private final PaymentMapper paymentMapper;
    private final ProductMapper productMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PaymentVO pay(Long userId, String orderNo, String paymentMethod) {
        OrderInfo order = orderInfoMapper.selectOne(
                new LambdaQueryWrapper<OrderInfo>().eq(OrderInfo::getOrderNo, orderNo));
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "订单不存在");
        }
        if (order.getStatus() != OrderStatusEnum.PENDING_PAYMENT.getValue()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "订单状态不允许支付");
        }
        // 已超 30 分钟（定时关单任务尚未执行）→ 拒绝支付，由定时任务负责关单+恢复库存
        if (order.getCreatedAt() != null
                && order.getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(30))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "订单已超时，请重新下单");
        }

        // 生成交易流水号 + 写支付记录
        String paymentNo = "PAY" + LocalDateTime.now().format(PAY_NO_FMT)
                + ThreadLocalRandom.current().nextInt(1000, 9999);
        Payment payment = new Payment();
        payment.setOrderNo(orderNo);
        payment.setUserId(userId);
        payment.setPaymentNo(paymentNo);
        payment.setPaymentMethod(paymentMethod == null ? "MOCK" : paymentMethod);
        payment.setAmount(order.getActualAmount());
        payment.setStatus(1); // Mock 直接成功
        payment.setPayTime(LocalDateTime.now());
        paymentMapper.insert(payment);

        // 更新订单为已付款
        order.setStatus(OrderStatusEnum.PAID.getValue());
        order.setPaymentAmount(order.getActualAmount());
        order.setPaymentMethod(payment.getPaymentMethod());
        order.setPaymentTime(payment.getPayTime());
        orderInfoMapper.updateById(order);

        // 累加销量
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, order.getId()));
        for (OrderItem item : items) {
            productMapper.incrementSales(item.getProductId(), item.getQuantity());
        }

        return new PaymentVO(paymentNo, payment.getStatus(), payment.getPayTime(), payment.getAmount());
    }

    @Override
    public PaymentVO status(Long userId, String orderNo) {
        Payment payment = paymentMapper.selectOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getOrderNo, orderNo)
                .eq(Payment::getUserId, userId)
                .orderByDesc(Payment::getId)
                .last("LIMIT 1"));
        if (payment == null) {
            return new PaymentVO(null, 0, null, null);
        }
        return new PaymentVO(payment.getPaymentNo(), payment.getStatus(), payment.getPayTime(), payment.getAmount());
    }
}
