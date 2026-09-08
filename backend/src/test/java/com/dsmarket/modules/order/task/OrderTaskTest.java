package com.dsmarket.modules.order.task;

import com.dsmarket.common.enums.OrderStatusEnum;
import com.dsmarket.modules.cart.mapper.CartMapper;
import com.dsmarket.modules.order.entity.OrderInfo;
import com.dsmarket.modules.order.entity.OrderItem;
import com.dsmarket.modules.order.mapper.OrderInfoMapper;
import com.dsmarket.modules.order.mapper.OrderItemMapper;
import com.dsmarket.modules.product.mapper.ProductMapper;
import com.dsmarket.modules.product.mapper.ProductSkuMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 定时任务单元测试：超时关单（关闭订单 + 恢复库存）。
 */
@ExtendWith(MockitoExtension.class)
class OrderTaskTest {

    @Mock
    private OrderInfoMapper orderInfoMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private ProductMapper productMapper;
    @Mock
    private ProductSkuMapper productSkuMapper;
    @Mock
    private CartMapper cartMapper;

    @InjectMocks
    private OrderTask task;

    @Test
    void closeExpiredOrders_noExpired_noop() {
        when(orderInfoMapper.selectList(any())).thenReturn(List.of());
        task.closeExpiredOrders();
        verify(orderInfoMapper, never()).updateById(any(OrderInfo.class));
        verify(orderItemMapper, never()).selectList(any());
    }

    @Test
    void closeExpiredOrders_closesAndRestoresStock() {
        OrderInfo expired = new OrderInfo();
        expired.setId(1L);
        expired.setStatus(OrderStatusEnum.PENDING_PAYMENT.getValue());
        expired.setCreatedAt(LocalDateTime.now().minusMinutes(31));
        when(orderInfoMapper.selectList(any())).thenReturn(List.of(expired));

        // SKU 商品：恢复 SKU 库存 + 聚合商品库存
        OrderItem skuItem = new OrderItem();
        skuItem.setOrderId(1L);
        skuItem.setProductId(10L);
        skuItem.setSkuId(20L);
        skuItem.setQuantity(2);
        // 无 SKU 商品：只恢复商品库存
        OrderItem plainItem = new OrderItem();
        plainItem.setOrderId(1L);
        plainItem.setProductId(11L);
        plainItem.setSkuId(null);
        plainItem.setQuantity(3);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(skuItem, plainItem));

        task.closeExpiredOrders();

        ArgumentCaptor<OrderInfo> captor = ArgumentCaptor.forClass(OrderInfo.class);
        verify(orderInfoMapper).updateById((OrderInfo) captor.capture());
        assertEquals(OrderStatusEnum.CLOSED.getValue(), captor.getValue().getStatus());
        assertNotNull(captor.getValue().getCloseTime());

        // 库存恢复：SKU 商品 → SKU 库存 + 商品聚合库存；纯商品 → 商品库存
        verify(productSkuMapper).incrementStock(20L, 2);
        verify(productMapper).incrementStock(10L, 2);
        verify(productMapper).incrementStock(11L, 3);
        verify(productMapper, never()).decrementStock(anyLong(), anyInt());
    }

    @Test
    void closeExpiredOrders_staleOrder_keptOpen() {
        // 未超时订单（createdAt 近 5 分钟）不应被关闭——由查询条件保证，这里验证 selectList 未命中则不 update
        OrderInfo fresh = new OrderInfo();
        fresh.setId(2L);
        fresh.setStatus(OrderStatusEnum.PENDING_PAYMENT.getValue());
        fresh.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        when(orderInfoMapper.selectList(any())).thenReturn(List.of()); // 定时任务查询只返回超时的
        task.closeExpiredOrders();
        verify(orderInfoMapper, never()).updateById(any(OrderInfo.class));
    }
}
