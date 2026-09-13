package com.dsmarket.modules.product.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsmarket.modules.product.entity.Product;
import com.dsmarket.modules.product.mapper.ProductMapper;
import com.dsmarket.modules.shop.entity.Shop;
import com.dsmarket.modules.shop.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ProductVisibility#SQL} 判据本身的集成测试（真实 PostgreSQL / 独立 {@code dsmarket_test} 库）。
 *
 * <p><b>为什么判据要单独测</b>：它是四个落点共用的唯一事实来源，写错一次等于四处同时错；
 * 而它接进服务层之后，错的形态是"商品少了几件"，不是报错 —— 那种故障最容易被当成数据问题。
 * 趁它还是孤立片段时把语义钉死，比事后从列表页反推便宜得多。</p>
 *
 * <p>每条"不可见"断言都配一条**正向对照**（本该可见的确实可见），
 * 否则"谓词写成了永假"也能让全部隐藏用例通过 —— 与
 * {@code MerchantProductIsolationIntegrationTest} 同一副纪律。</p>
 *
 * <p>测试数据以 {@code PVTEST-} 前缀命名，{@code @BeforeEach} 按前缀清理，
 * 不依赖库的初始状态，也不依赖执行顺序。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ProductVisibilityIntegrationTest {

    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private ShopMapper shopMapper;
    @Autowired
    private JdbcTemplate jdbc;

    /** 高位段避开库里已有账号；dsm_shop.user_id 有唯一约束，四家店须用四个不同 id */
    private static final Long UID_OPEN = 910001L;
    private static final Long UID_PENDING = 910002L;
    private static final Long UID_CLOSED = 910003L;
    private static final Long UID_DELETED = 910004L;

    private Long shopOpen;
    private Long shopPending;
    private Long shopRejected;
    private Long shopDeleted;

    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM dsm_product_sku WHERE product_id IN (SELECT id FROM dsm_product WHERE name LIKE 'PVTEST-%')");
        jdbc.update("DELETE FROM dsm_product WHERE name LIKE 'PVTEST-%'");
        jdbc.update("DELETE FROM dsm_shop WHERE shop_name LIKE 'PVTEST-%'");

        shopOpen = insertShop(UID_OPEN, "PVTEST-开通店", 1);
        shopPending = insertShop(UID_PENDING, "PVTEST-待审店", 0);
        // 2 = 已驳回（Q1 之后「关闭」改用 3，两者语义已分开）。
        // 这里保留 2 是因为它同样落进「非 1 即不可见」，顺带把这条边界钉住；
        // 真正的终局关闭（3）由 ProductPurchaseGuardIntegrationTest 覆盖。
        shopRejected = insertShop(UID_CLOSED, "PVTEST-驳回店", 2);
        shopDeleted = insertShop(UID_DELETED, "PVTEST-软删店", 1);
        // 软删要走 deleteById：@TableLogic 才会去改 deleted，手写 set 不生效
        shopMapper.deleteById(shopDeleted);
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
    private Long insertProduct(String name, Long shopId, Integer status) {
        Product p = new Product();
        p.setName(name);
        p.setTitle("可见性测试");
        p.setBrief("可见性测试");
        p.setCategoryId(1L);
        p.setPrice(new BigDecimal("66.00"));
        p.setStock(10);
        p.setSales(0);
        p.setStatus(status);
        p.setShopId(shopId);
        productMapper.insert(p);
        return p.getId();
    }

    /** 用被测判据查询本类造的数据（按前缀收窄，不依赖库里其他内容） */
    private Set<Long> visibleIds() {
        LambdaQueryWrapper<Product> w = new LambdaQueryWrapper<>();
        w.likeRight(Product::getName, "PVTEST-");
        ProductVisibility.apply(w);
        return Set.copyOf(productMapper.selectList(w).stream().map(Product::getId).toList());
    }

    // ---------- 平台自营分支（shop_id IS NULL）----------

    @Test
    void selfOperated_onSale_visible() {
        Long id = insertProduct("PVTEST-自营上架", null, 1);
        assertTrue(visibleIds().contains(id), "平台自营上架商品必须公开可见（E4）");
    }

    @Test
    void selfOperated_offShelf_hidden() {
        Long id = insertProduct("PVTEST-自营下架", null, 0);
        assertFalse(visibleIds().contains(id), "自营下架商品不得可见");
    }

    // ---------- 商家店铺分支：正向对照 ----------

    @Test
    void openShop_onSale_visible() {
        Long id = insertProduct("PVTEST-开通店上架", shopOpen, 1);
        assertTrue(visibleIds().contains(id),
                "开通店铺的上架商品必须可见 —— 这条是下面所有「不可见」断言的对照组，"
                        + "缺了它，谓词写成永假也能全绿");
    }

    @Test
    void openShop_offShelf_hidden() {
        Long id = insertProduct("PVTEST-开通店下架", shopOpen, 0);
        assertFalse(visibleIds().contains(id), "店铺开着，但商品自己下架了，仍不可见");
    }

    // ---------- 商家店铺分支：三种「不可见」成因，必须逐一区分 ----------

    @Test
    void rejectedShop_onSaleProduct_hidden() {
        Long id = insertProduct("PVTEST-驳回店上架", shopRejected, 1);
        assertFalse(visibleIds().contains(id),
                "店铺已驳回(status=2)，其上架商品必须全站不可见 —— 本 REQ 要补的契约缺口正是指这条");
    }

    @Test
    void pendingShop_onSaleProduct_hidden() {
        Long id = insertProduct("PVTEST-待审店上架", shopPending, 1);
        assertFalse(visibleIds().contains(id), "店铺待审核(status=0)未开通，其商品不得可见");
    }

    @Test
    void softDeletedShop_onSaleProduct_hidden() {
        Long id = insertProduct("PVTEST-软删店上架", shopDeleted, 1);
        // 这条专门盯子查询里手写的 s.deleted = 0：@TableLogic 管不到原生 SQL，
        // 漏写时店铺虽被软删、status 仍是 1，商品会静默地继续可见
        assertFalse(visibleIds().contains(id), "店铺已软删(deleted=1)，其商品不得可见");
    }

    // ---------- 强断言：不得有假阳性 ----------

    @Test
    void visibilityPredicate_selectsExactlyTheExpectedRows() {
        Long selfOn = insertProduct("PVTEST-自营上架", null, 1);
        Long selfOff = insertProduct("PVTEST-自营下架", null, 0);
        Long openOn = insertProduct("PVTEST-开通店上架", shopOpen, 1);
        Long openOff = insertProduct("PVTEST-开通店下架", shopOpen, 0);
        insertProduct("PVTEST-驳回店上架", shopRejected, 1);
        insertProduct("PVTEST-待审店上架", shopPending, 1);
        insertProduct("PVTEST-软删店上架", shopDeleted, 1);

        // 上面逐条测的是"该藏的藏了"，这条测的是"不该藏的没被误伤"。
        // 逐条断言各自为政，一个过宽的谓词可以同时满足它们中的每一条。
        assertEquals(Set.of(selfOn, openOn), visibleIds(),
                "可见集合必须恰好是 {自营上架, 开通店上架}");
    }

    // ---------- 反向对照：谓词不得溢出到"不该用它"的语义 ----------

    @Test
    void predicate_doesNotSwallowOtherStatuses_soItMustNotBeUsedOffStorefront() {
        Long offShelfSelfOperated = insertProduct("PVTEST-自营下架", null, 0);
        Long closedShopProduct = insertProduct("PVTEST-驳回店上架", shopRejected, 1);

        // 不加判据时两条都能查到 —— 这正是管理端 searchAllStatus 需要的语义。
        // 若把本判据误用到管理端/商家侧，这两条会静默消失，且后台不报错。
        List<Long> withoutPredicate = productMapper.selectList(
                        new LambdaQueryWrapper<Product>().likeRight(Product::getName, "PVTEST-"))
                .stream().map(Product::getId).toList();

        assertTrue(withoutPredicate.contains(offShelfSelfOperated));
        assertTrue(withoutPredicate.contains(closedShopProduct));
        // 而加上判据后两条都被滤掉 —— 判据的作用是实打实的，不是个摆设
        assertFalse(visibleIds().contains(offShelfSelfOperated));
        assertFalse(visibleIds().contains(closedShopProduct));
    }
}
