package com.dsmarket.modules.product.service;

import com.dsmarket.common.domain.PageResult;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.modules.product.dto.ProductFormDTO;
import com.dsmarket.modules.product.dto.ProductListVO;
import com.dsmarket.modules.product.dto.ProductQuery;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 商家数据隔离集成测试（真实 PostgreSQL / 独立 {@code dsmarket_test} 库，需已应用 V10）。
 *
 * <p>单测只能证明"分支走对了"，证明不了"SQL 里真的带上了 {@code shop_id}"——
 * 隔离失效恰恰是那种"状态码全对、数据全错"的故障，必须用真库把行查出来数一遍。
 * 本类的每条隔离断言都配一条**反向对照**（本该可见的东西确实可见），
 * 否则"全都查不到"也能伪装成通过。</p>
 *
 * <p>测试数据一律以 {@code MITEST-} 前缀命名，{@code @BeforeEach} 按前缀清理，
 * 不依赖库的初始状态，也不依赖执行顺序。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class MerchantProductIsolationIntegrationTest {

    @Autowired
    private ProductService productService;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private ShopMapper shopMapper;
    @Autowired
    private ProductSearchService productSearchService;
    @Autowired
    private JdbcTemplate jdbc;

    /** 两个商家的用户ID。用高位段避开库里已有账号；dsm_shop.user_id 无外键，无需真建用户 */
    private static final Long USER_A = 900001L;
    private static final Long USER_B = 900002L;

    private Long shopA;
    private Long shopB;

    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM dsm_product_sku WHERE product_id IN (SELECT id FROM dsm_product WHERE name LIKE 'MITEST-%')");
        jdbc.update("DELETE FROM dsm_product WHERE name LIKE 'MITEST-%'");
        jdbc.update("DELETE FROM dsm_shop WHERE shop_name LIKE 'MITEST-%'");
        shopA = insertShop(USER_A, "MITEST-ShopA");
        shopB = insertShop(USER_B, "MITEST-ShopB");
    }

    private Long insertShop(Long userId, String name) {
        Shop s = new Shop();
        s.setUserId(userId);
        s.setShopName(name);
        s.setStatus(1);
        shopMapper.insert(s);
        return s.getId();
    }

    /** shopId=null 即平台自营（ADMIN 建的商品） */
    private Long insertProduct(String name, Long shopId, Integer status) {
        Product p = new Product();
        p.setName(name);
        p.setTitle("隔离测试");
        p.setBrief("隔离测试");
        p.setCategoryId(1L);
        p.setPrice(new BigDecimal("199.00"));
        p.setStock(10);
        p.setSales(0);
        p.setStatus(status);
        p.setShopId(shopId);
        productMapper.insert(p);
        return p.getId();
    }

    private static ProductQuery blank() {
        return new ProductQuery();
    }

    private static List<Long> idsOf(PageResult<ProductListVO> r) {
        return r.getRecords().stream().map(ProductListVO::getId).toList();
    }

    // ---------- 列表隔离 ----------

    @Test
    void merchantPage_returnsOnlyOwnShop_includingOffShelf_excludingPlatformOwned() {
        Long a1 = insertProduct("MITEST-A上架", shopA, 1);
        Long a2 = insertProduct("MITEST-A下架", shopA, 0);
        Long b1 = insertProduct("MITEST-B上架", shopB, 1);
        Long selfOp = insertProduct("MITEST-自营", null, 1);

        List<Long> got = idsOf(productService.merchantPage(USER_A, 1, 50, blank()));

        // 正向：自家的两条都在，**含已下架**（商家要能看到自己下架的商品）
        assertTrue(got.contains(a1), "自家上架商品必须可见");
        assertTrue(got.contains(a2), "自家**已下架**商品也必须可见（商家侧不过滤 status）");
        // 反向：别家的、平台自营的，一条都不能出现
        assertFalse(got.contains(b1), "别家店铺商品泄漏进商家列表");
        assertFalse(got.contains(selfOp), "平台自营（shop_id IS NULL）泄漏进商家列表");
    }

    @Test
    void searchByShop_doesNotDegradeToPlatformWide() {
        insertProduct("MITEST-A上架", shopA, 1);
        insertProduct("MITEST-B上架", shopB, 1);
        insertProduct("MITEST-自营", null, 1);

        PageResult<ProductListVO> r = productService.merchantPage(USER_A, 1, 50, blank());

        // 全库共 3 条，商家 A 只能看到 1 条。数量对不上就说明隔离没生效
        assertEquals(1, r.getRecords().size(),
                "商家 A 应只看到自家 1 条，实际=" + idsOf(r));
    }

    // ---------- 跨租户 by-id：404 且不落库 ----------

    @Test
    void getMerchantDetail_crossShop_throws404() {
        Long b1 = insertProduct("MITEST-B上架", shopB, 1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> productService.getMerchantDetail(USER_A, b1));
        assertEquals(404, ex.getCode(), "跨店铺访问必须是 404（403 会泄露 id 存在性）");
    }

    @Test
    void getMerchantDetail_ownProduct_returns_it() {
        // 反向对照：没有这条，"全 404" 也能伪装成隔离生效
        Long a1 = insertProduct("MITEST-A上架", shopA, 1);

        assertEquals(a1, productService.getMerchantDetail(USER_A, a1).getId());
    }

    @Test
    void updateForShop_crossShop_throws404_andRowByteIdentical() {
        Long b1 = insertProduct("MITEST-B上架", shopB, 1);
        Map<String, Object> before = jdbc.queryForMap(
                "SELECT name, price, status, shop_id FROM dsm_product WHERE id = ?", b1);

        ProductFormDTO form = new ProductFormDTO();
        form.setName("MITEST-HIJACKED");
        form.setCategoryId(1L);
        form.setPrice(new BigDecimal("0.01"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> productService.updateForShop(USER_A, b1, form));
        assertEquals(404, ex.getCode());

        // 只断言状态码会放过"先改了再报错"的实现；必须回读真库逐列比对（REQ §12 纪律）
        Map<String, Object> after = jdbc.queryForMap(
                "SELECT name, price, status, shop_id FROM dsm_product WHERE id = ?", b1);
        assertEquals(before, after, "跨租户 PUT 不得改动目标商品任何一个字段");
        assertEquals("MITEST-B上架", after.get("name"));
    }

    @Test
    void updateStatusForShop_crossShop_cannotOffShelfOthersProduct() {
        Long b1 = insertProduct("MITEST-B上架", shopB, 1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> productService.updateStatusForShop(USER_A, b1, 0));
        assertEquals(404, ex.getCode());
        assertEquals(1, jdbc.queryForObject(
                "SELECT status FROM dsm_product WHERE id = ?", Integer.class, b1),
                "别家商品的状态不得被改动");
    }

    // ---------- 归属由服务端赋值 ----------

    @Test
    void createForShop_persistsOwnShopId() {
        ProductFormDTO form = new ProductFormDTO();
        form.setName("MITEST-新建");
        form.setCategoryId(1L);
        form.setPrice(new BigDecimal("88.00"));

        Long id = productService.createForShop(USER_A, form);

        assertEquals(shopA, jdbc.queryForObject(
                "SELECT shop_id FROM dsm_product WHERE id = ?", Long.class, id));
    }

    // ---------- 回归基线：隔离不得溢到商城前台（E9）----------

    @Test
    void storefront_platformWideSearch_stillSeesEveryShop() {
        Long a1 = insertProduct("MITEST-A上架", shopA, 1);
        Long b1 = insertProduct("MITEST-B上架", shopB, 1);
        Long selfOp = insertProduct("MITEST-自营", null, 1);

        // 商城前台（ProductSearchService.search）**不带** shopId：所有上架商品都应可见。
        // 这条是整个改动最容易踩的坑——隔离只作用于"商家管理自己的商品"，
        // 顺手套到前台的后果是商家商品在 C 端集体消失，且后台全绿。
        List<Long> got = productSearchService.search(1, 200, blank()).getRecords()
                .stream().map(ProductListVO::getId).toList();

        assertTrue(got.contains(a1), "商家 A 的商品必须仍对 C 端可见");
        assertTrue(got.contains(b1), "商家 B 的商品必须仍对 C 端可见");
        assertTrue(got.contains(selfOp), "平台自营商品必须仍对 C 端可见");
    }

    @Test
    void storefront_keywordSearch_alsoStaysPlatformWide() {
        Long a1 = insertProduct("MITEST-A上架", shopA, 1);
        Long b1 = insertProduct("MITEST-B上架", shopB, 1);

        // 关键词路径与无关键词路径是**两次不同的 doSearch 调用**。隔离若写错位置
        // （比如写进某个分支），最容易漏的就是带关键词这条。关键词 "MITEST" 无论走
        // 全文检索还是零命中后的 LIKE 兜底，两家商品都应命中。
        List<Long> got = productSearchService.search(1, 200, kw("MITEST")).getRecords()
                .stream().map(ProductListVO::getId).toList();

        assertTrue(got.contains(a1), "带关键词的前台检索漏掉了商家 A 的商品");
        assertTrue(got.contains(b1), "带关键词的前台检索漏掉了商家 B 的商品");
    }

    private static ProductQuery kw(String keyword) {
        ProductQuery q = new ProductQuery();
        q.setKeyword(keyword);
        return q;
    }
}
