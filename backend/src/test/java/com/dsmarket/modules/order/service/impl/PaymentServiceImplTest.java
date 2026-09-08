package com.dsmarket.modules.order.service.impl;

import com.dsmarket.common.enums.OrderStatusEnum;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.modules.order.dto.PaymentVO;
import com.dsmarket.modules.order.entity.OrderInfo;
import com.dsmarket.modules.order.entity.OrderItem;
import com.dsmarket.modules.order.entity.Payment;
import com.dsmarket.modules.order.mapper.OrderInfoMapper;
import com.dsmarket.modules.order.mapper.OrderItemMapper;
import com.dsmarket.modules.order.mapper.PaymentMapper;
import com.dsmarket.modules.product.mapper.ProductMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 支付 Service 层单元测试：Mock 支付（成功/超时/状态不符/非本人）。
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock
    private OrderInfoMapper orderInfoMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private PaymentMapper paymentMapper;
    @Mock
    private ProductMapper productMapper;

    @InjectMocks
    private PaymentServiceImpl service;

    @Test
    void pay_orderNotOwned_throws() {
        OrderInfo order = order(1L, 2L, OrderStatusEnum.PENDING_PAYMENT.getValue());
        when(orderInfoMapper.selectOne(any())).thenReturn(order);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.pay(3L, "DSM1", "MOCK"));
        assertEquals("订单不存在", ex.getMessage());
    }

    @Test
    void pay_wrongStatus_throws() {
        OrderInfo order = order(1L, 1L, OrderStatusEnum.PAID.getValue());
        when(orderInfoMapper.selectOne(any())).thenReturn(order);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.pay(1L, "DSM1", "MOCK"));
        assertEquals("订单状态不允许支付", ex.getMessage());
    }

    @Test
    void pay_timeout_throws() {
        OrderInfo order = order(1L, 1L, OrderStatusEnum.PENDING_PAYMENT.getValue());
        order.setCreatedAt(LocalDateTime.now().minusMinutes(31)); // 超过 30 分钟
        when(orderInfoMapper.selectOne(any())).thenReturn(order);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.pay(1L, "DSM1", "MOCK"));
        assertEquals("订单已超时，请重新下单", ex.getMessage());
        // 超时分支只拒绝支付，不写支付记录、不改订单
        verify(paymentMapper, never()).insert(any(Payment.class));
        verify(orderInfoMapper, never()).updateById(any(OrderInfo.class));
    }

    @Test
    void pay_success_writesPaymentUpdatesOrderIncrementsSales() {
        OrderInfo order = order(1L, 1L, OrderStatusEnum.PENDING_PAYMENT.getValue());
        order.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        order.setActualAmount(new BigDecimal("99.00"));
        when(orderInfoMapper.selectOne(any())).thenReturn(order);

        OrderItem item = new OrderItem();
        item.setOrderId(1L);
        item.setProductId(10L);
        item.setQuantity(2);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item));

        PaymentVO vo = service.pay(1L, "DSM1", "MOCK");

        assertNotNull(vo.getPaymentNo());
        assertTrue(vo.getPaymentNo().startsWith("PAY"));
        assertEquals(1, vo.getStatus());
        assertEquals(new BigDecimal("99.00"), vo.getAmount());

        // 支付记录写入
        ArgumentCaptor<Payment> payCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentMapper).insert((Payment) payCaptor.capture());
        assertEquals("DSM1", payCaptor.getValue().getOrderNo());
        assertEquals(1, payCaptor.getValue().getStatus());

        // 订单更新为已付款
        ArgumentCaptor<OrderInfo> orderCaptor = ArgumentCaptor.forClass(OrderInfo.class);
        verify(orderInfoMapper).updateById((OrderInfo) orderCaptor.capture());
        assertEquals(OrderStatusEnum.PAID.getValue(), orderCaptor.getValue().getStatus());
        assertEquals(new BigDecimal("99.00"), orderCaptor.getValue().getPaymentAmount());
        assertNotNull(orderCaptor.getValue().getPaymentTime());

        // 销量累加
        verify(productMapper).incrementSales(10L, 2);
    }

    private OrderInfo order(Long id, Long userId, int status) {
        OrderInfo order = new OrderInfo();
        order.setId(id);
        order.setUserId(userId);
        order.setOrderNo("DSM" + id);
        order.setStatus(status);
        order.setTotalAmount(new BigDecimal("99.00"));
        order.setActualAmount(new BigDecimal("99.00"));
        return order;
    }
}
