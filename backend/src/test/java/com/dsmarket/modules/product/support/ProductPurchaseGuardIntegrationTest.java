package com.dsmarket.modules.product.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsmarket.common.enums.ShopStatusEnum;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.modules.address.entity.Address;
import com.dsmarket.modules.address.mapper.AddressMapper;
import com.dsmarket.modules.cart.dto.CartItemVO;
import com.dsmarket.modules.cart.service.CartService;
import com.dsmarket.modules.order.dto.CreateOrderRequest;
import com.dsmarket.modules.order.dto.OrderCreateResult;
import com.dsmarket.modules.order.entity.OrderInfo;
import com.dsmarket.modules.order.mapper.OrderInfoMapper;
import com.dsmarket.modules.order.service.OrderService;
import com.dsmarket.modules.product.entity.Product;
import com.dsmarket.modules.product.mapper.ProductMapper;
import com.dsmarket.modules.shop.entity.Shop;
import com.dsmarket.modules.shop.mapper.ShopMapper;
import com.dsmarket.modules.user.entity.User;
import com.dsmarket.modules.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 写路径（加购 / 下单）店铺校验的真库集成测试（真实 PostgreSQL / 独立 {@code dsmarket_test} 库）。
 *
 * <p><b>为什么这层不可省</b>：单元测试里 {@code ProductMapper} 是 mock，判据的 SQL 根本没被执行
 * —— 把 {@link ProductVisibility#SQL} 整个写错，那些单测照样全绿。只有走真库才能证明
 * 「守门人查得到、且查得对」。这正是「突变必须打在探针够得着的那一层」。</p>
 *
 * <p>本类补的是 Q6 实测确认的缺口：关店后**下单仍成功**（订单落库 ¥188）、
 * **且仍能重新加购**（{@code POST /cart} 返回 200）。</p>
 *
 * <p>每条「关店后买不了」的断言都配一条**正向对照**（同一批数据、店铺开着时确实买得成），
 * 否则「守门人一律拒绝」也会让全部拦截用例通过 —— 与
 * {@code ProductVisibilityIntegrationTest}、{@code MerchantProductIsolationIntegrationTest}
 * 同一副纪律。</p>
 *
 * <p>测试数据以 {@code WPTEST-} 前缀命名（店铺名）/ {@code wptest_} 前缀（用户名），
 * {@code @BeforeEach} 按前缀清理，不依赖库的初始状态，也不依赖执行顺序。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ProductPurchaseGuardIntegrationTest {

    @Autowired
    private ProductPurchaseGuard guard;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private ShopMapper shopMapper;
    @Autowired
    private CartService cartService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderInfoMapper orderInfoMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private AddressMapper addressMapper;
    @Autowired
    private JdbcTemplate jdbc;

    /** 高位段避开库里已有账号；dsm_shop.user_id 有唯一约束 */
    private static final Long SHOP_OWNER_ID = 950001L;

    private Long shopId;
    private Long merchantProductId;
    private Long selfOperatedProductId;
    private Long buyerId;
    private Long addressId;

    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM dsm_order_item WHERE order_id IN (SELECT id FROM dsm_order_info "
                + "WHERE user_id IN (SELECT id FROM dsm_user WHERE username LIKE 'wptest\\_%'))");
        jdbc.update("DELETE FROM dsm_order_info WHERE user_id IN "
                + "(SELECT id FROM dsm_user WHERE username LIKE 'wptest\\_%')");
        jdbc.update("DELETE FROM dsm_cart WHERE user_id IN "
                + "(SELECT id FROM dsm_user WHERE username LIKE 'wptest\\_%')");
        jdbc.update("DELETE FROM dsm_address WHERE user_id IN "
                + "(SELECT id FROM dsm_user WHERE username LIKE 'wptest\\_%')");
        jdbc.update("DELETE FROM dsm_product_sku WHERE product_id IN "
                + "(SELECT id FROM dsm_product WHERE name LIKE 'WPTEST-%')");
        jdbc.update("DELETE FROM dsm_product WHERE name LIKE 'WPTEST-%'");
        jdbc.update("DELETE FROM dsm_shop WHERE shop_name LIKE 'WPTEST-%'");
        jdbc.update("DELETE FROM dsm_user WHERE username LIKE 'wptest\\_%'");

        shopId = insertShop(SHOP_OWNER_ID, "WPTEST-商家店铺", ShopStatusEnum.OPEN.getValue());
        merchantProductId = insertProduct("WPTEST-店商品", shopId);
        selfOperatedProductId = insertProduct("WPTEST-自营商品", null);
        buyerId = insertBuyer("wptest_buyer");
        addressId = insertAddress(buyerId).getId();
    }

    // ---------- 正向对照：店铺开着时买得成 ----------

    @Test
    void openShop_addToCartAndCheckout_bothSucceed() {
        cartService.add(buyerId, merchantProductId, null, 1);
        OrderCreateResult result = orderService.create(buyerId, req());
        assertNotNull(result.getOrderNo(), "店铺开通时，商家商品必须加得进购物车、下得了单 —— "
                + "这条是下面所有「关店后买不了」断言的对照组，缺了它，守门人一律拒绝也能全绿");
    }

    @Test
    void selfOperated_addToCartAndCheckout_bothSucceed() {
        cartService.add(buyerId, selfOperatedProductId, null, 1);
        assertNotNull(orderService.create(buyerId, req()).getOrderNo());
    }

    // ---------- 关店后：加购被拦 ----------

    @Test
    void closedShop_addToCart_rejected() {
        closeShop();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.add(buyerId, merchantProductId, null, 1));
        assertEquals("商品所属店铺已关闭: WPTEST-店商品", ex.getMessage());

        // 一行都不许写：光断言抛异常会漏掉「抛之前已经插进去了」
        assertEquals(0, countCartRows(), "关店后加购必须一行都不写");
    }

    // ---------- 关店后：购物车里已有的商品结不了账 ----------

    @Test
    void closedShop_checkoutOfPreClosedCartItem_rejectedAndWritesNothing() {
        // 还原真机现场：**关店前**已放进购物车（这一步当时是合法的）
        cartService.add(buyerId, merchantProductId, null, 1);
        assertEquals(1, countCartRows());
        closeShop();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.create(buyerId, req()));
        assertEquals("商品所属店铺已关闭: WPTEST-店商品", ex.getMessage());

        // 必须限定到本测试的买家：selectCount(null) 会数全库订单，
        // 库里任何别的残留订单都会让这条断言假红（"关店后不得产生任何订单"读起来像产品缺陷）
        assertEquals(0, orderInfoMapper.selectCount(
                        new LambdaQueryWrapper<OrderInfo>().eq(OrderInfo::getUserId, buyerId)),
                "关店后不得产生任何订单");
        assertEquals(10, productMapper.selectById(merchantProductId).getStock(), "库存不得被扣");
        assertEquals(1, countCartRows(), "购物车不得被清空 —— 让用户还能看见、自己删掉");
    }

    // ---------- 边界：购物车列表仍显示该行（本轮有意不做静默清理）----------

    @Test
    void closedShop_existingCartRow_stillListedButUnbuyable() {
        // 本轮只拦「加购」与「下单」，不清洗既有购物车行（见 REQ §11 不包含范围）。
        // 把这条边界写成断言而不是留白：用户会看到该行 + 结账时收到明确文案。
        cartService.add(buyerId, merchantProductId, null, 1);
        closeShop();

        List<CartItemVO> items = cartService.list(buyerId);
        assertEquals(1, items.size(), "既有购物车行仍在列表里（有意为之，非缺陷）");
        assertEquals(merchantProductId, items.get(0).getProductId());
        assertThrows(BusinessException.class, () -> orderService.create(buyerId, req()));
    }

    // ---------- 自营商品不受店铺状态影响 ----------

    @Test
    void closedShop_selfOperatedProduct_unaffected() {
        closeShop();

        // 判据里 `shop_id IS NULL` 是短路分支 —— 这是本类最容易误伤的一处：
        // 若守门人写成「先查店铺、查不到就拒」，平台自营商品会全线买不了。
        cartService.add(buyerId, selfOperatedProductId, null, 1);
        assertNotNull(orderService.create(buyerId, req()).getOrderNo());
    }

    // ---------- 判据本身：status=3 必须落进「不可见」 ----------

    @Test
    void guard_rejectsOnTerminallyClosedShop() {
        // Q1 新增的 status=3「已关闭」。此前「3 会自动落进 != 1」只是推断，无测试覆盖。
        jdbc.update("UPDATE dsm_shop SET status = ? WHERE id = ?", ShopStatusEnum.CLOSED.getValue(), shopId);

        BusinessException ex = assertThrows(BusinessException.class, () -> guard.requirePurchasable(merchantProductId));
        assertEquals("商品所属店铺已关闭: WPTEST-店商品", ex.getMessage());
    }

    // ---------- 辅助 ----------

    private void closeShop() {
        // 用 3（终局关闭）而非 2（已驳回）—— Q1 之后两者语义已分开
        jdbc.update("UPDATE dsm_shop SET status = ? WHERE id = ?", ShopStatusEnum.CLOSED.getValue(), shopId);
    }

    private int countCartRows() {
        return jdbc.queryForObject("SELECT count(*) FROM dsm_cart WHERE user_id = ?", Integer.class, buyerId);
    }

    private Long insertShop(Long userId, String name, Integer status) {
        Shop s = new Shop();
        s.setUserId(userId);
        s.setShopName(name);
        s.setStatus(status);
        shopMapper.insert(s);
        return s.getId();
    }

    /** shopId=null 即平台自营 */
    private Long insertProduct(String name, Long shopId) {
        Product p = new Product();
        p.setName(name);
        p.setTitle(name);
        p.setPrice(new BigDecimal("66.00"));
        p.setOriginalPrice(new BigDecimal("66.00"));
        p.setStock(10);
        p.setSales(0);
        p.setCategoryId(1L);
        p.setStatus(1);
        p.setHasSku(0);
        p.setIsFeatured(0);
        p.setShopId(shopId);
        p.setMainImage("/uploads/wptest.jpg");
        productMapper.insert(p);
        return p.getId();
    }

    private Long insertBuyer(String username) {
        User u = new User();
        u.setUsername(username);
        u.setPassword("test-pass");
        u.setNickname("写路径测试买家");
        u.setRole("USER");
        u.setStatus(1);
        userMapper.insert(u);
        return u.getId();
    }

    private Address insertAddress(Long userId) {
        Address a = new Address();
        a.setUserId(userId);
        a.setReceiverName("测试收件人");
        a.setReceiverPhone("13800000000");
        a.setProvince("广东省");
        a.setCity("深圳市");
        a.setDistrict("南山区");
        a.setDetailAddress("写路径测试路 1 号");
        a.setIsDefault(1);
        addressMapper.insert(a);
        return a;
    }

    private CreateOrderRequest req() {
        CreateOrderRequest r = new CreateOrderRequest();
        r.setAddressId(addressId);
        return r;
    }
}
