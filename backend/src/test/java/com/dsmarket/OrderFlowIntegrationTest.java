package com.dsmarket;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsmarket.common.enums.OrderStatusEnum;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.modules.address.entity.Address;
import com.dsmarket.modules.address.mapper.AddressMapper;
import com.dsmarket.modules.cart.entity.Cart;
import com.dsmarket.modules.cart.mapper.CartMapper;
import com.dsmarket.modules.order.dto.CreateOrderRequest;
import com.dsmarket.modules.order.dto.OrderCreateResult;
import com.dsmarket.modules.order.dto.PaymentVO;
import com.dsmarket.modules.order.entity.OrderInfo;
import com.dsmarket.modules.order.entity.OrderItem;
import com.dsmarket.modules.order.entity.Payment;
import com.dsmarket.modules.order.mapper.OrderInfoMapper;
import com.dsmarket.modules.order.mapper.OrderItemMapper;
import com.dsmarket.modules.order.mapper.PaymentMapper;
import com.dsmarket.modules.order.service.OrderService;
import com.dsmarket.modules.order.service.PaymentService;
import com.dsmarket.modules.order.task.OrderTask;
import com.dsmarket.modules.product.entity.Product;
import com.dsmarket.modules.product.entity.ProductSku;
import com.dsmarket.modules.product.mapper.ProductMapper;
import com.dsmarket.modules.product.mapper.ProductSkuMapper;
import com.dsmarket.modules.user.entity.User;
import com.dsmarket.modules.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 订单流程集成测试（真实 PostgreSQL/Redis，独立 dsmarket_test 库）：
 * 完整创建→支付链路、事务回滚、取消恢复库存、定时关单、SKU 双库存、并发防超卖。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderFlowIntegrationTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private OrderTask orderTask;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private ProductSkuMapper productSkuMapper;
    @Autowired
    private CartMapper cartMapper;
    @Autowired
    private AddressMapper addressMapper;
    @Autowired
    private OrderInfoMapper orderInfoMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private PaymentMapper paymentMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanSlate() {
        // 物理清除上一轮可能残留的测试数据（含失败用例的残留）
        jdbc.update("DELETE FROM dsm_order_item WHERE order_id IN "
                + "(SELECT id FROM dsm_order_info WHERE user_id IN (SELECT id FROM dsm_user WHERE username LIKE 'itest_%'))");
        jdbc.update("DELETE FROM dsm_payment WHERE user_id IN (SELECT id FROM dsm_user WHERE username LIKE 'itest_%')");
        jdbc.update("DELETE FROM dsm_order_info WHERE user_id IN (SELECT id FROM dsm_user WHERE username LIKE 'itest_%')");
        jdbc.update("DELETE FROM dsm_cart WHERE user_id IN (SELECT id FROM dsm_user WHERE username LIKE 'itest_%')");
        jdbc.update("DELETE FROM dsm_address WHERE user_id IN (SELECT id FROM dsm_user WHERE username LIKE 'itest_%')");
        jdbc.update("DELETE FROM dsm_product_sku WHERE product_id IN (SELECT id FROM dsm_product WHERE name LIKE 'ITEST-%')");
        jdbc.update("DELETE FROM dsm_product WHERE name LIKE 'ITEST-%'");
        jdbc.update("DELETE FROM dsm_user WHERE username LIKE 'itest_%'");
    }

    // ---------- 完整链路：创建 → 支付 ----------

    @Test
    void createAndPay_fullFlow() {
        Product p = newProduct("ITEST-全链路", 20, "88.00");
        User u = newUser("itest_flow");
        Long addrId = newAddress(u.getId()).getId();
        cart(u.getId(), p.getId(), null, 2);

        OrderCreateResult created = orderService.create(u.getId(), req(addrId));
        OrderInfo order = byNo(created.getOrderNo());
        assertEquals(OrderStatusEnum.PENDING_PAYMENT.getValue(), order.getStatus());
        // 88*2 = 176 ≥ 99 → 免运费
        assertEquals(0, new BigDecimal("176.00").compareTo(order.getActualAmount()));

        PaymentVO vo = paymentService.pay(u.getId(), created.getOrderNo(), "MOCK");
        assertEquals(1, vo.getStatus());

        OrderInfo paid = byNo(created.getOrderNo());
        assertEquals(OrderStatusEnum.PAID.getValue(), paid.getStatus());
        assertNotNull(paid.getPaymentTime());
        assertEquals(0, new BigDecimal("176.00").compareTo(paid.getPaymentAmount()));
        // 支付记录已写入
        assertEquals(1, paymentMapper.selectCount(
                new LambdaQueryWrapper<Payment>().eq(Payment::getOrderNo, created.getOrderNo())));
        // 销量累加
        assertEquals(2, productMapper.selectById(p.getId()).getSales());
        // 购物车已清空
        assertEquals(0, cartMapper.selectCount(
                new LambdaQueryWrapper<Cart>().eq(Cart::getUserId, u.getId())));
    }

    // ---------- 事务回滚：扣库存失败（预检通过但原子扣减失败） ----------

    @Test
    void create_insufficientStock_rollsBackFully() {
        Product p = newProduct("ITEST-回滚", 1, "10.00");
        User u = newUser("itest_rollback");
        Long addrId = newAddress(u.getId()).getId();
        cart(u.getId(), p.getId(), null, 5); // 需求 5 > 库存 1

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.create(u.getId(), req(addrId)));
        assertEquals("商品库存不足: ITEST-回滚", ex.getMessage());

        // 无订单、无订单项、库存未变、购物车未清空
        assertEquals(0, orderInfoMapper.selectCount(
                new LambdaQueryWrapper<OrderInfo>().eq(OrderInfo::getUserId, u.getId())));
        assertEquals(0, orderItemMapper.selectCount(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, -1L)));
        assertEquals(1, productMapper.selectById(p.getId()).getStock());
        assertEquals(1, cartMapper.selectCount(
                new LambdaQueryWrapper<Cart>().eq(Cart::getUserId, u.getId())));
    }

    // ---------- 取消订单恢复库存 ----------

    @Test
    void cancel_restoresStockAndIdempotent() {
        Product p = newProduct("ITEST-取消", 5, "50.00");
        User u = newUser("itest_cancel");
        Long addrId = newAddress(u.getId()).getId();
        cart(u.getId(), p.getId(), null, 2);

        OrderCreateResult created = orderService.create(u.getId(), req(addrId));
        assertEquals(3, productMapper.selectById(p.getId()).getStock()); // 5-2

        orderService.cancel(u.getId(), created.getOrderNo(), "测试取消");
        OrderInfo cancelled = byNo(created.getOrderNo());
        assertEquals(OrderStatusEnum.CANCELLED.getValue(), cancelled.getStatus());
        assertEquals(5, productMapper.selectById(p.getId()).getStock()); // 恢复

        orderService.cancel(u.getId(), created.getOrderNo(), "再取消"); // 幂等不报错
        assertEquals(5, productMapper.selectById(p.getId()).getStock()); // 库存不再重复恢复
    }

    // ---------- SKU 商品：SKU 库存 + 商品聚合库存双扣 ----------

    @Test
    void skuOrder_deductsSkuAndAggregateStock() {
        Product p = newProduct("ITEST-SKU", 10, "0.00");
        p.setHasSku(1);
        productMapper.updateById(p);
        ProductSku sku = newSku(p.getId(), "99.00", 10);
        User u = newUser("itest_sku");
        Long addrId = newAddress(u.getId()).getId();
        cart(u.getId(), p.getId(), sku.getId(), 2);

        OrderCreateResult created = orderService.create(u.getId(), req(addrId));
        assertEquals(OrderStatusEnum.PENDING_PAYMENT.getValue(), byNo(created.getOrderNo()).getStatus());
        assertEquals(8, productSkuMapper.selectById(sku.getId()).getStock());   // SKU 扣 2
        assertEquals(8, productMapper.selectById(p.getId()).getStock());        // 聚合扣 2
    }

    // ---------- 定时关单（等价于调短时间手动触发） ----------

    @Test
    void closeExpiredOrders_closesAndRestoresStock() {
        Product p = newProduct("ITEST-关单", 5, "50.00");
        User u = newUser("itest_close");
        Long addrId = newAddress(u.getId()).getId();
        cart(u.getId(), p.getId(), null, 2);
        OrderCreateResult created = orderService.create(u.getId(), req(addrId));
        assertEquals(3, productMapper.selectById(p.getId()).getStock());

        // 把订单时间改回 31 分钟前，模拟超时未支付
        OrderInfo stale = byNo(created.getOrderNo());
        stale.setCreatedAt(LocalDateTime.now().minusMinutes(31));
        orderInfoMapper.updateById(stale);

        orderTask.closeExpiredOrders(); // 手动触发（等价于定时任务周期扫描）

        OrderInfo closed = byNo(created.getOrderNo());
        assertEquals(OrderStatusEnum.CLOSED.getValue(), closed.getStatus());
        assertNotNull(closed.getCloseTime());
        assertEquals(5, productMapper.selectById(p.getId()).getStock()); // 恢复
    }

    // ---------- 并发防超卖：原子条件更新 ----------

    @Test
    void concurrentOrders_noOversell() throws Exception {
        // 库存 10，6 个用户各下 2 件 → 恰好 5 单成功，最终库存 0（绝不为负）
        Product p = newProduct("ITEST-并发防超卖", 10, "99.00");
        int THREADS = 6;
        int QTY = 2;
        List<Long> userIds = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            User u = newUser("itest_conc_" + i);
            newAddress(u.getId());
            userIds.add(u.getId());
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (Long uid : userIds) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                try {
                    cart(uid, p.getId(), null, QTY);
                    Long addrId = addressMapper.selectOne(
                            new LambdaQueryWrapper<Address>().eq(Address::getUserId, uid)).getId();
                    orderService.create(uid, req(addrId));
                    return true;
                } catch (BusinessException e) {
                    return false;
                } catch (Exception e) {
                    return false;
                }
            }));
        }
        ready.await(10, TimeUnit.SECONDS);
        start.countDown();

        int success = 0;
        for (Future<Boolean> f : futures) {
            if (Boolean.TRUE.equals(f.get(30, TimeUnit.SECONDS))) {
                success++;
            }
        }
        pool.shutdownNow();

        // 10 库存 / 每单 2 件 = 5 单成功
        assertEquals(5, success);
        Product refreshed = productMapper.selectById(p.getId());
        assertEquals(0, refreshed.getStock());
        assertTrue(refreshed.getStock() >= 0, "库存不能为负");

        // 恰好 5 笔订单
        assertEquals(5, orderInfoMapper.selectCount(
                new LambdaQueryWrapper<OrderInfo>().in(OrderInfo::getUserId, userIds)));
    }

    // ---------- 测试数据辅助 ----------

    private Product newProduct(String name, int stock, String price) {
        Product p = new Product();
        p.setName(name);
        p.setTitle(name);
        p.setPrice(new BigDecimal(price));
        p.setOriginalPrice(new BigDecimal(price));
        p.setStock(stock);
        p.setSales(0);
        p.setCategoryId(1L);
        p.setStatus(1);
        p.setHasSku(0);
        p.setIsFeatured(0);
        p.setMainImage("/uploads/itest.jpg");
        productMapper.insert(p);
        return p;
    }

    private ProductSku newSku(Long productId, String price, int stock) {
        ProductSku s = new ProductSku();
        s.setProductId(productId);
        s.setSkuCode("SKU-ITEST");
        s.setPrice(new BigDecimal(price));
        s.setStock(stock);
        s.setSpecs("[{\"name\":\"颜色\",\"value\":\"黑色\"}]");
        s.setStatus(1);
        productSkuMapper.insert(s);
        return s;
    }

    private User newUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("test-pass");
        u.setNickname("集成测试");
        u.setRole("USER");
        u.setStatus(1);
        userMapper.insert(u);
        return u;
    }

    private Address newAddress(Long userId) {
        Address a = new Address();
        a.setUserId(userId);
        a.setReceiverName("测试收件人");
        a.setReceiverPhone("13800000000");
        a.setProvince("广东省");
        a.setCity("深圳市");
        a.setDistrict("南山区");
        a.setDetailAddress("集成测试路 1 号");
        a.setIsDefault(1);
        addressMapper.insert(a);
        return a;
    }

    private void cart(Long userId, Long productId, Long skuId, int qty) {
        Cart c = new Cart();
        c.setUserId(userId);
        c.setProductId(productId);
        c.setSkuId(skuId);
        c.setQuantity(qty);
        c.setChecked(1);
        cartMapper.insert(c);
    }

    private CreateOrderRequest req(Long addressId) {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setAddressId(addressId);
        return req;
    }

    private OrderInfo byNo(String orderNo) {
        return orderInfoMapper.selectOne(
                new LambdaQueryWrapper<OrderInfo>().eq(OrderInfo::getOrderNo, orderNo));
    }
}
