package com.dsmarket.modules.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.common.enums.OrderStatusEnum;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.address.entity.Address;
import com.dsmarket.modules.address.mapper.AddressMapper;
import com.dsmarket.modules.cart.entity.Cart;
import com.dsmarket.modules.cart.mapper.CartMapper;
import com.dsmarket.modules.order.dto.AddressSnapshot;
import com.dsmarket.modules.order.dto.AdminOrderListVO;
import com.dsmarket.modules.order.dto.AdminOrderRow;
import com.dsmarket.modules.order.dto.CreateOrderRequest;
import com.dsmarket.modules.order.dto.OrderCreateResult;
import com.dsmarket.modules.order.dto.OrderDetailVO;
import com.dsmarket.modules.order.dto.OrderItemVO;
import com.dsmarket.modules.order.dto.OrderListVO;
import com.dsmarket.modules.order.dto.TimelineItem;
import com.dsmarket.modules.order.entity.OrderInfo;
import com.dsmarket.modules.order.entity.OrderItem;
import com.dsmarket.modules.order.mapper.OrderInfoMapper;
import com.dsmarket.modules.order.mapper.OrderItemMapper;
import com.dsmarket.modules.order.service.OrderService;
import com.dsmarket.modules.order.util.OrderNoGenerator;
import com.dsmarket.modules.product.dto.SpecItem;
import com.dsmarket.modules.product.entity.Product;
import com.dsmarket.modules.product.entity.ProductSku;
import com.dsmarket.modules.product.mapper.ProductMapper;
import com.dsmarket.modules.product.mapper.ProductSkuMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private static final BigDecimal FREE_SHIPPING_THRESHOLD = new BigDecimal("99");
    private static final BigDecimal SHIPPING_FEE = new BigDecimal("10");

    private final OrderInfoMapper orderInfoMapper;
    private final OrderItemMapper orderItemMapper;
    private final CartMapper cartMapper;
    private final AddressMapper addressMapper;
    private final ProductMapper productMapper;
    private final ProductSkuMapper productSkuMapper;
    private final ObjectMapper objectMapper;
    private final OrderNoGenerator orderNoGenerator;

    @Override
    @Transactional(timeout = 30, rollbackFor = Exception.class)
    public OrderCreateResult create(Long userId, CreateOrderRequest request) {
        // 1. 查询已勾选购物车
        List<Cart> carts = cartMapper.selectList(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getUserId, userId).eq(Cart::getChecked, 1));
        if (carts.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "请先勾选要购买的商品");
        }

        // 2. 校验收货地址
        if (request.getAddressId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "请选择收货地址");
        }
        Address address = addressMapper.selectById(request.getAddressId());
        if (address == null || !address.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "收货地址无效");
        }

        // 3. 逐项库存校验 + 组装订单项
        BigDecimal total = BigDecimal.ZERO;
        List<OrderItem> orderItems = new ArrayList<>();
        for (Cart cart : carts) {
            Product product = productMapper.selectById(cart.getProductId());
            if (product == null || product.getStatus() != 1) {
                throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "商品已下架或不存在");
            }
            ProductSku sku = cart.getSkuId() != null ? productSkuMapper.selectById(cart.getSkuId()) : null;
            if (cart.getSkuId() != null && (sku == null || sku.getStatus() != 1)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "商品规格已失效: " + product.getName());
            }
            int stock = sku != null
                    ? (sku.getStock() == null ? 0 : sku.getStock())
                    : (product.getStock() == null ? 0 : product.getStock());
            if (stock < cart.getQuantity()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "商品库存不足: " + product.getName());
            }
            BigDecimal unitPrice = sku != null && sku.getPrice() != null ? sku.getPrice() : product.getPrice();
            BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(cart.getQuantity()));
            total = total.add(subtotal);

            OrderItem item = new OrderItem();
            item.setProductId(product.getId());
            item.setSkuId(cart.getSkuId());
            item.setProductName(product.getName());
            item.setProductImage(product.getMainImage());
            item.setSkuSpecs(sku != null ? sku.getSpecs() : null);
            item.setUnitPrice(unitPrice);
            item.setQuantity(cart.getQuantity());
            item.setSubtotal(subtotal);
            orderItems.add(item);
        }

        // 4. 金额计算：满99免邮，否则10元运费
        BigDecimal shipping = total.compareTo(FREE_SHIPPING_THRESHOLD) >= 0 ? BigDecimal.ZERO : SHIPPING_FEE;
        BigDecimal actual = total.add(shipping);

        // 5. 生成订单号 + 插入订单主表
        String orderNo = orderNoGenerator.generate();
        OrderInfo order = new OrderInfo();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setTotalAmount(total);
        order.setShippingFee(shipping);
        order.setDiscountAmount(BigDecimal.ZERO);
        order.setActualAmount(actual);
        order.setStatus(OrderStatusEnum.PENDING_PAYMENT.getValue());
        order.setRemark(request.getRemark());
        order.setAddressSnapshot(toAddressSnapshotJson(address));
        orderInfoMapper.insert(order);

        // 6. 插入订单项（批量）
        for (OrderItem item : orderItems) {
            item.setOrderId(order.getId());
            orderItemMapper.insert(item);
        }

        // 7. 扣库存（原子条件更新，防超卖）
        for (Cart cart : carts) {
            int affected;
            if (cart.getSkuId() != null) {
                affected = productSkuMapper.deductStock(cart.getSkuId(), cart.getQuantity());
            } else {
                affected = productMapper.deductStock(cart.getProductId(), cart.getQuantity());
            }
            if (affected == 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "商品库存不足，下单失败");
            }
            // SKU 商品同步聚合库存（无条件，实际可用库存以 SKU 为准）
            if (cart.getSkuId() != null) {
                productMapper.decrementStock(cart.getProductId(), cart.getQuantity());
            }
        }

        // 8. 清空已勾选购物车
        cartMapper.delete(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getUserId, userId).eq(Cart::getChecked, 1));

        return new OrderCreateResult(orderNo, actual, order.getStatus());
    }

    @Override
    public PageResult<OrderListVO> list(Long userId, Integer status, long page, long size) {
        LambdaQueryWrapper<OrderInfo> wrapper = new LambdaQueryWrapper<OrderInfo>()
                .eq(OrderInfo::getUserId, userId)
                .eq(status != null, OrderInfo::getStatus, status)
                .orderByDesc(OrderInfo::getId);
        Page<OrderInfo> p = orderInfoMapper.selectPage(new Page<>(page, size), wrapper);

        List<OrderListVO> vos = new ArrayList<>();
        for (OrderInfo order : p.getRecords()) {
            OrderListVO vo = new OrderListVO();
            vo.setOrderNo(order.getOrderNo());
            vo.setTotalAmount(order.getTotalAmount());
            vo.setShippingFee(order.getShippingFee());
            vo.setActualAmount(order.getActualAmount());
            vo.setStatus(order.getStatus());
            vo.setStatusName(OrderStatusEnum.fromValue(order.getStatus()).getDisplayName());
            vo.setCreatedAt(order.getCreatedAt());
            List<OrderItemVO> itemVOs = toItemVOs(order.getId());
            vo.setOrderItems(itemVOs);
            vo.setItemCount(itemVOs.stream().mapToInt(i -> i.getQuantity() == null ? 0 : i.getQuantity()).sum());
            vos.add(vo);
        }
        return PageResult.of(vos, p.getTotal(), page, size);
    }

    @Override
    public OrderDetailVO detail(Long userId, String orderNo) {
        OrderInfo order = requireOwnedByNo(userId, orderNo);
        OrderDetailVO vo = new OrderDetailVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setShippingFee(order.getShippingFee());
        vo.setDiscountAmount(order.getDiscountAmount());
        vo.setActualAmount(order.getActualAmount());
        vo.setStatus(order.getStatus());
        vo.setStatusName(OrderStatusEnum.fromValue(order.getStatus()).getDisplayName());
        vo.setRemark(order.getRemark());
        vo.setPaymentMethod(order.getPaymentMethod());
        vo.setCreatedAt(order.getCreatedAt());
        vo.setAddress(parseAddressSnapshot(order.getAddressSnapshot()));
        vo.setOrderItems(toItemVOs(order.getId()));
        vo.setTimeline(buildTimeline(order));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, String orderNo, String reason) {
        OrderInfo order = requireOwnedByNo(userId, orderNo);
        if (order.getStatus() == OrderStatusEnum.CANCELLED.getValue()) {
            return; // 幂等：已取消直接成功
        }
        if (order.getStatus() != OrderStatusEnum.PENDING_PAYMENT.getValue()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "仅待付款订单可取消");
        }
        order.setStatus(OrderStatusEnum.CANCELLED.getValue());
        order.setCancelTime(LocalDateTime.now());
        order.setCancelReason(reason);
        orderInfoMapper.updateById(order);
        restoreStock(order.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void receive(Long userId, String orderNo) {
        OrderInfo order = requireOwnedByNo(userId, orderNo);
        if (order.getStatus() != OrderStatusEnum.SHIPPED.getValue()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "仅已发货订单可确认收货");
        }
        order.setStatus(OrderStatusEnum.RECEIVED.getValue());
        order.setReceiveTime(LocalDateTime.now());
        orderInfoMapper.updateById(order);
    }

    @Override
    public PageResult<AdminOrderListVO> adminPage(Integer status, String keyword, long page, long size) {
        IPage<AdminOrderRow> p = orderInfoMapper.selectAdminPage(new Page<>(page, size), status, keyword);
        List<AdminOrderRow> rows = p.getRecords();

        // 批量统计各订单商品件数
        Map<Long, Integer> itemCountMap = new HashMap<>();
        if (!rows.isEmpty()) {
            List<Long> orderIds = rows.stream().map(AdminOrderRow::getId).toList();
            List<OrderItem> items = orderItemMapper.selectList(
                    new LambdaQueryWrapper<OrderItem>().in(OrderItem::getOrderId, orderIds));
            for (OrderItem item : items) {
                itemCountMap.merge(item.getOrderId(),
                        item.getQuantity() == null ? 0 : item.getQuantity(), Integer::sum);
            }
        }

        List<AdminOrderListVO> vos = rows.stream().map(row -> {
            AdminOrderListVO vo = new AdminOrderListVO();
            vo.setOrderNo(row.getOrderNo());
            vo.setBuyerName(row.getBuyerName());
            vo.setTotalAmount(row.getTotalAmount());
            vo.setShippingFee(row.getShippingFee());
            vo.setActualAmount(row.getActualAmount());
            vo.setStatus(row.getStatus());
            vo.setStatusName(OrderStatusEnum.fromValue(row.getStatus()).getDisplayName());
            vo.setItemCount(itemCountMap.getOrDefault(row.getId(), 0));
            vo.setPaymentTime(row.getPaymentTime());
            vo.setDeliveryTime(row.getDeliveryTime());
            vo.setCreatedAt(row.getCreatedAt());
            return vo;
        }).toList();
        return PageResult.of(vos, p.getTotal(), page, size);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ship(String orderNo) {
        OrderInfo order = orderInfoMapper.selectOne(
                new LambdaQueryWrapper<OrderInfo>().eq(OrderInfo::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "订单不存在");
        }
        if (order.getStatus() != OrderStatusEnum.PAID.getValue()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "仅已付款订单可发货");
        }
        order.setStatus(OrderStatusEnum.SHIPPED.getValue());
        order.setDeliveryTime(LocalDateTime.now());
        orderInfoMapper.updateById(order);
    }

    // ---------- 私有工具 ----------

    private OrderInfo requireOwnedByNo(Long userId, String orderNo) {
        OrderInfo order = orderInfoMapper.selectOne(
                new LambdaQueryWrapper<OrderInfo>().eq(OrderInfo::getOrderNo, orderNo));
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "订单不存在");
        }
        return order;
    }

    private List<OrderItemVO> toItemVOs(Long orderId) {
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, orderId));
        List<OrderItemVO> vos = new ArrayList<>();
        for (OrderItem item : items) {
            OrderItemVO vo = new OrderItemVO();
            vo.setProductId(item.getProductId());
            vo.setSkuId(item.getSkuId());
            vo.setProductName(item.getProductName());
            vo.setProductImage(item.getProductImage());
            vo.setSkuSpecs(parseSpecs(item.getSkuSpecs()));
            vo.setUnitPrice(item.getUnitPrice());
            vo.setQuantity(item.getQuantity());
            vo.setSubtotal(item.getSubtotal());
            vos.add(vo);
        }
        return vos;
    }

    private List<TimelineItem> buildTimeline(OrderInfo order) {
        List<TimelineItem> timeline = new ArrayList<>();
        int status = order.getStatus() == null ? 0 : order.getStatus();
        timeline.add(new TimelineItem(0, "提交订单", order.getCreatedAt()));
        if (order.getPaymentTime() != null) {
            timeline.add(new TimelineItem(OrderStatusEnum.PAID.getValue(), "付款成功", order.getPaymentTime()));
        }
        if (order.getDeliveryTime() != null) {
            timeline.add(new TimelineItem(OrderStatusEnum.SHIPPED.getValue(), "商品已发货", order.getDeliveryTime()));
        }
        if (order.getReceiveTime() != null) {
            timeline.add(new TimelineItem(OrderStatusEnum.RECEIVED.getValue(), "确认收货", order.getReceiveTime()));
        }
        if (status == OrderStatusEnum.CANCELLED.getValue() && order.getCancelTime() != null) {
            timeline.add(new TimelineItem(OrderStatusEnum.CANCELLED.getValue(), "订单已取消", order.getCancelTime()));
        }
        if (status == OrderStatusEnum.CLOSED.getValue() && order.getCloseTime() != null) {
            timeline.add(new TimelineItem(OrderStatusEnum.CLOSED.getValue(), "订单已关闭", order.getCloseTime()));
        }
        return timeline;
    }

    /** 取消/关单时恢复库存 */
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

    private String toAddressSnapshotJson(Address address) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("receiverName", address.getReceiverName());
        map.put("receiverPhone", address.getReceiverPhone());
        map.put("province", address.getProvince());
        map.put("city", address.getCity());
        map.put("district", address.getDistrict());
        map.put("detailAddress", address.getDetailAddress());
        map.put("zipCode", address.getZipCode());
        map.put("label", address.getLabel());
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR.getCode(), "订单创建失败，请稍后再试");
        }
    }

    private AddressSnapshot parseAddressSnapshot(String json) {
        if (!StringUtils.hasText(json)) {
            return new AddressSnapshot();
        }
        try {
            return objectMapper.readValue(json, AddressSnapshot.class);
        } catch (Exception e) {
            return new AddressSnapshot();
        }
    }

    private List<SpecItem> parseSpecs(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<SpecItem>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
