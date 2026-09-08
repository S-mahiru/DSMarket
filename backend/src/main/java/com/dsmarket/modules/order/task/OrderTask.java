package com.dsmarket.modules.order.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsmarket.common.enums.OrderStatusEnum;
import com.dsmarket.modules.cart.entity.Cart;
import com.dsmarket.modules.cart.mapper.CartMapper;
import com.dsmarket.modules.order.entity.OrderInfo;
import com.dsmarket.modules.order.entity.OrderItem;
import com.dsmarket.modules.order.mapper.OrderInfoMapper;
import com.dsmarket.modules.order.mapper.OrderItemMapper;
import com.dsmarket.modules.product.mapper.ProductMapper;
import com.dsmarket.modules.product.mapper.ProductSkuMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时任务：超时关单 + 废弃购物车清理
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTask {

    private static final long ORDER_TIMEOUT_MINUTES = 30;

    private final OrderInfoMapper orderInfoMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final ProductSkuMapper productSkuMapper;
    private final CartMapper cartMapper;

    /** 每 5 分钟执行一次：关闭超时未支付订单并恢复库存 */
    @Scheduled(fixedRate = 300000)
    @Transactional(rollbackFor = Exception.class)
    public void closeExpiredOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(ORDER_TIMEOUT_MINUTES);
        List<OrderInfo> expired = orderInfoMapper.selectList(new LambdaQueryWrapper<OrderInfo>()
                .eq(OrderInfo::getStatus, OrderStatusEnum.PENDING_PAYMENT.getValue())
                .lt(OrderInfo::getCreatedAt, deadline));
        if (expired.isEmpty()) {
            return;
        }
        for (OrderInfo order : expired) {
            order.setStatus(OrderStatusEnum.CLOSED.getValue());
            order.setCloseTime(LocalDateTime.now());
            orderInfoMapper.updateById(order);
            restoreStock(order.getId());
        }
        log.info("定时关单完成：关闭 {} 笔超时订单", expired.size());
    }

    /** 每日凌晨 3 点：清理超过 30 天未更新的废弃购物车 */
    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void cleanExpiredCarts() {
        LocalDateTime deadline = LocalDateTime.now().minusDays(30);
        int deleted = cartMapper.delete(new LambdaQueryWrapper<Cart>().lt(Cart::getUpdatedAt, deadline));
        if (deleted > 0) {
            log.info("清理废弃购物车 {} 条", deleted);
        }
    }

    private void restoreStock(Long orderId) {
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
        for (OrderItem item : items) {
            if (item.getSkuId() != null) {
                productSkuMapper.incrementStock(item.getSkuId(), item.getQuantity());
            }
            productMapper.incrementStock(item.getProductId(), item.getQuantity());
        }
    }
}
