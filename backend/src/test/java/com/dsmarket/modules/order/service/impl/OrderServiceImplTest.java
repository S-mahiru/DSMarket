package com.dsmarket.modules.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsmarket.common.enums.OrderStatusEnum;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.modules.address.entity.Address;
import com.dsmarket.modules.address.mapper.AddressMapper;
import com.dsmarket.modules.cart.entity.Cart;
import com.dsmarket.modules.cart.mapper.CartMapper;
import com.dsmarket.modules.order.dto.CreateOrderRequest;
import com.dsmarket.modules.order.dto.OrderCreateResult;
import com.dsmarket.modules.order.entity.OrderInfo;
import com.dsmarket.modules.order.entity.OrderItem;
import com.dsmarket.modules.order.mapper.OrderInfoMapper;
import com.dsmarket.modules.order.mapper.OrderItemMapper;
import com.dsmarket.modules.order.util.OrderNoGenerator;
import com.dsmarket.modules.product.entity.Product;
import com.dsmarket.modules.product.mapper.ProductMapper;
import com.dsmarket.modules.product.mapper.ProductSkuMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 订单 Service 层单元测试：订单创建（事务扣库存）、取消（幂等+恢复库存）、收货、发货。
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderInfoMapper orderInfoMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private CartMapper cartMapper;
    @Mock
    private AddressMapper addressMapper;
    @Mock
    private ProductMapper productMapper;
    @Mock
    private ProductSkuMapper productSkuMapper;
    @Mock
    private OrderNoGenerator orderNoGenerator;

    @InjectMocks
    private OrderServiceImpl service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // @InjectMocks 无法注入 final ObjectMapper 字段的 mock，这里通过反射/构造不可行，
        // 改为在 setter 注入真实实例（@InjectMocks 对 final 字段回退为无参——所以直接 new 并赋值）。
        setField("objectMapper", objectMapper);
    }

    private void setField(String name, Object value) {
        try {
            var field = OrderServiceImpl.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(service, value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- 创建订单 ----------

    @Test
    void create_emptyCart_throws() {
        when(cartMapper.selectList(any())).thenReturn(List.of());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(1L, new CreateOrderRequest()));
        assertEquals("请先勾选要购买的商品", ex.getMessage());
    }

    @Test
    void create_missingAddress_throws() {
        when(cartMapper.selectList(any())).thenReturn(List.of(cart()));
        CreateOrderRequest req = new CreateOrderRequest();
        req.setAddressId(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(1L, req));
        assertEquals("请选择收货地址", ex.getMessage());
    }

    @Test
    void create_addressNotOwned_throws() {
        when(cartMapper.selectList(any())).thenReturn(List.of(cart()));
        when(addressMapper.selectById(100L)).thenReturn(address(2L)); // 归属他人
        CreateOrderRequest req = new CreateOrderRequest();
        req.setAddressId(100L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(1L, req));
        assertEquals("收货地址无效", ex.getMessage());
    }

    @Test
    void create_insufficientStock_throws() {
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(10L, null, 5)));
        when(addressMapper.selectById(100L)).thenReturn(address(1L));
        Product product = product(10L, "测试商品", "40.00", 2); // 库存 2 < 需求 5
        when(productMapper.selectById(10L)).thenReturn(product);
        CreateOrderRequest req = new CreateOrderRequest();
        req.setAddressId(100L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(1L, req));
        assertEquals("商品库存不足: 测试商品", ex.getMessage());
        verify(productSkuMapper, never()).deductStock(anyLong(), anyInt());
    }

    @Test
    void create_skuInvalid_throws() {
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(10L, 20L, 1)));
        when(addressMapper.selectById(100L)).thenReturn(address(1L));
        when(productMapper.selectById(10L)).thenReturn(product(10L, "SKU商品", "99.00", 99));
        when(productSkuMapper.selectById(20L)).thenReturn(null); // SKU 已失效
        CreateOrderRequest req = new CreateOrderRequest();
        req.setAddressId(100L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(1L, req));
        assertEquals("商品规格已失效: SKU商品", ex.getMessage());
    }

    @Test
    void create_deductStockFailure_rollsBack() {
        // 扣库存返回 0 → 抛异常（事务回滚由 @Transactional 处理，单测验证异常抛出）
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(10L, null, 1)));
        when(addressMapper.selectById(100L)).thenReturn(address(1L));
        when(productMapper.selectById(10L)).thenReturn(product(10L, "热销商品", "40.00", 10));
        when(orderNoGenerator.generate()).thenReturn("DSM2026081600000001");
        when(orderInfoMapper.insert(any(OrderInfo.class))).thenAnswer(inv -> {
            inv.getArgument(0, OrderInfo.class).setId(999L);
            return 1;
        });
        when(productMapper.deductStock(10L, 1)).thenReturn(0); // 原子扣库存失败（并发被抢）
        CreateOrderRequest req = new CreateOrderRequest();
        req.setAddressId(100L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(1L, req));
        assertEquals("商品库存不足，下单失败", ex.getMessage());
        // 购物车不应被清空（异常中断在清空前）
        verify(cartMapper, never()).delete(any());
    }

    @Test
    void create_success_freeShippingAndCleanCart() {
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(10L, null, 2)));
        when(addressMapper.selectById(100L)).thenReturn(address(1L));
        when(productMapper.selectById(10L)).thenReturn(product(10L, "测试商品", "40.00", 100));
        when(orderNoGenerator.generate()).thenReturn("DSM2026081600000001");
        when(orderInfoMapper.insert(any(OrderInfo.class))).thenAnswer(inv -> {
            inv.getArgument(0, OrderInfo.class).setId(999L);
            return 1;
        });
        when(productMapper.deductStock(10L, 2)).thenReturn(1);

        CreateOrderRequest req = new CreateOrderRequest();
        req.setAddressId(100L);
        OrderCreateResult result = service.create(1L, req);

        // 40*2 = 80 < 99 → 收 10 元运费，实付 90
        assertEquals("DSM2026081600000001", result.getOrderNo());
        assertEquals(0, result.getStatus());
        assertEquals(new BigDecimal("90.00"), result.getActualAmount());

        // 订单主表、订单项、扣库存、清购物车都发生
        ArgumentCaptor<OrderInfo> orderCaptor = ArgumentCaptor.forClass(OrderInfo.class);
        verify(orderInfoMapper).insert((OrderInfo) orderCaptor.capture());
        assertEquals(0, orderCaptor.getValue().getStatus().intValue());
        assertEquals(new BigDecimal("80.00"), orderCaptor.getValue().getTotalAmount());
        assertEquals(0, new BigDecimal("10.00").compareTo(orderCaptor.getValue().getShippingFee())); // 10 vs 10.00 scale 不同，用 compareTo
        assertTrue(orderCaptor.getValue().getAddressSnapshot().contains("张小明"));

        verify(orderItemMapper).insert(any(OrderItem.class));
        verify(productMapper).deductStock(10L, 2);
        verify(cartMapper).delete(any());
    }

    @Test
    void create_success_over99FreeShipping() {
        when(cartMapper.selectList(any())).thenReturn(List.of(cart(10L, null, 3))); // 3*40=120 ≥99
        when(addressMapper.selectById(100L)).thenReturn(address(1L));
        when(productMapper.selectById(10L)).thenReturn(product(10L, "测试商品", "40.00", 100));
        when(orderNoGenerator.generate()).thenReturn("DSM2026081600000002");
        when(orderInfoMapper.insert(any(OrderInfo.class))).thenAnswer(inv -> {
            inv.getArgument(0, OrderInfo.class).setId(999L);
            return 1;
        });
        when(productMapper.deductStock(10L, 3)).thenReturn(1);

        CreateOrderRequest req = new CreateOrderRequest();
        req.setAddressId(100L);
        OrderCreateResult result = service.create(1L, req);
        assertEquals(new BigDecimal("120.00"), result.getActualAmount()); // 免运费
    }

    // ---------- 取消订单 ----------

    @Test
    void cancel_orderNotOwned_throws() {
        OrderInfo order = order(1L, 2L, OrderStatusEnum.PENDING_PAYMENT.getValue());
        when(orderInfoMapper.selectOne(any())).thenReturn(order);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.cancel(3L, "DSM1", null));
        assertEquals("订单不存在", ex.getMessage());
    }

    @Test
    void cancel_alreadyCancelled_idempotent() {
        OrderInfo order = order(1L, 1L, OrderStatusEnum.CANCELLED.getValue());
        when(orderInfoMapper.selectOne(any())).thenReturn(order);
        service.cancel(1L, "DSM1", "不想要了"); // 不应抛异常
        verify(orderInfoMapper, never()).updateById(any(OrderInfo.class));
        verify(orderItemMapper, never()).selectList(any());
    }

    @Test
    void cancel_wrongStatus_throws() {
        OrderInfo order = order(1L, 1L, OrderStatusEnum.PAID.getValue());
        when(orderInfoMapper.selectOne(any())).thenReturn(order);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.cancel(1L, "DSM1", null));
        assertEquals("仅待付款订单可取消", ex.getMessage());
    }

    @Test
    void cancel_success_restoreStock() {
        OrderInfo order = order(1L, 1L, OrderStatusEnum.PENDING_PAYMENT.getValue());
        when(orderInfoMapper.selectOne(any())).thenReturn(order);
        OrderItem item = new OrderItem();
        item.setOrderId(1L);
        item.setProductId(10L);
        item.setSkuId(null);
        item.setQuantity(2);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item));

        service.cancel(1L, "DSM1", "不想要了");

        ArgumentCaptor<OrderInfo> captor = ArgumentCaptor.forClass(OrderInfo.class);
        verify(orderInfoMapper).updateById((OrderInfo) captor.capture());
        assertEquals(OrderStatusEnum.CANCELLED.getValue(), captor.getValue().getStatus());
        assertEquals("不想要了", captor.getValue().getCancelReason());
        verify(productMapper).incrementStock(10L, 2); // 库存恢复
    }

    // ---------- 确认收货 ----------

    @Test
    void receive_notShipped_throws() {
        OrderInfo order = order(1L, 1L, OrderStatusEnum.PAID.getValue());
        when(orderInfoMapper.selectOne(any())).thenReturn(order);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.receive(1L, "DSM1"));
        assertEquals("仅已发货订单可确认收货", ex.getMessage());
    }

    @Test
    void receive_success() {
        OrderInfo order = order(1L, 1L, OrderStatusEnum.SHIPPED.getValue());
        when(orderInfoMapper.selectOne(any())).thenReturn(order);
        service.receive(1L, "DSM1");
        ArgumentCaptor<OrderInfo> captor = ArgumentCaptor.forClass(OrderInfo.class);
        verify(orderInfoMapper).updateById((OrderInfo) captor.capture());
        assertEquals(OrderStatusEnum.RECEIVED.getValue(), captor.getValue().getStatus());
        assertNotNull(captor.getValue().getReceiveTime());
    }

    // ---------- 管理员发货 ----------

    @Test
    void ship_notFound_throws() {
        when(orderInfoMapper.selectOne(any())).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.ship("DSM_NOT_EXIST"));
        assertEquals("订单不存在", ex.getMessage());
    }

    @Test
    void ship_notPaid_throws() {
        when(orderInfoMapper.selectOne(any())).thenReturn(order(1L, 1L, OrderStatusEnum.PENDING_PAYMENT.getValue()));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.ship("DSM1"));
        assertEquals("仅已付款订单可发货", ex.getMessage());
    }

    @Test
    void ship_success() {
        OrderInfo order = order(1L, 1L, OrderStatusEnum.PAID.getValue());
        when(orderInfoMapper.selectOne(any())).thenReturn(order);
        service.ship("DSM1");
        ArgumentCaptor<OrderInfo> captor = ArgumentCaptor.forClass(OrderInfo.class);
        verify(orderInfoMapper).updateById((OrderInfo) captor.capture());
        assertEquals(OrderStatusEnum.SHIPPED.getValue(), captor.getValue().getStatus());
        assertNotNull(captor.getValue().getDeliveryTime());
    }

    // ---------- 测试辅助 ----------

    private Cart cart() {
        return cart(10L, null, 2);
    }

    private Cart cart(Long productId, Long skuId, int qty) {
        Cart cart = new Cart();
        cart.setUserId(1L);
        cart.setProductId(productId);
        cart.setSkuId(skuId);
        cart.setQuantity(qty);
        cart.setChecked(1);
        return cart;
    }

    private Address address(Long userId) {
        Address address = new Address();
        address.setId(100L);
        address.setUserId(userId);
        address.setReceiverName("张小明");
        address.setReceiverPhone("13800000000");
        address.setProvince("广东省");
        address.setCity("深圳市");
        address.setDistrict("南山区");
        address.setDetailAddress("科技园路 1 号");
        return address;
    }

    private Product product(Long id, String name, String price, int stock) {
        Product product = new Product();
        product.setId(id);
        product.setName(name);
        product.setPrice(new BigDecimal(price));
        product.setStock(stock);
        product.setStatus(1);
        product.setMainImage("/uploads/x.jpg");
        return product;
    }

    private OrderInfo order(Long id, Long userId, int status) {
        OrderInfo order = new OrderInfo();
        order.setId(id);
        order.setUserId(userId);
        order.setOrderNo("DSM" + id);
        order.setStatus(status);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setActualAmount(new BigDecimal("100.00"));
        return order;
    }
}
