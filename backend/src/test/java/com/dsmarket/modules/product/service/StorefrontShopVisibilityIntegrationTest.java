package com.dsmarket.modules.product.service;

import com.dsmarket.common.domain.PageResult;
import com.dsmarket.common.exception.BusinessException;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 关闭店铺 → 其商品全站不可见的集成测试（REQ-20260913 §4.8 四个落点中的 1~3，§10 第 7 条）。
 *
 * <p><b>为什么必须用真库、且必须做前后对比</b>：本 REQ 要补的缺口是"审核驳回店铺后，
 * 其商品在前台照常可见可买"。这种故障的形态是**商品还在、页面不报错**，
 * 只看代码看不出漏没漏哪个落点。测试对**同一批数据**只改 {@code dsm_shop.status}，
 * 取"关之前 / 关之后 / 再开之后"三个截面 —— 再开之后必须恢复可见，
 * 这一条把"隐藏确实由店铺状态引起"与"这批数据本来就查不到"区分开。</p>
 *
 * <p>测试数据以 {@code SVTEST-} 前缀命名，{@code @BeforeEach} 按前缀清理。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class StorefrontShopVisibilityIntegrationTest {

    @Autowired
    private ProductService productService;
    @Autowired
    private ProductSearchService productSearchService;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private ShopMapper shopMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private static final Long UID = 920001L;

    private Long shopId;
    private Long productId;

    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM dsm_product_sku WHERE product_id IN (SELECT id FROM dsm_product WHERE name LIKE 'SVTEST-%')");
        jdbc.update("DELETE FROM dsm_product WHERE name LIKE 'SVTEST-%'");
        jdbc.update("DELETE FROM dsm_shop WHERE shop_name LIKE 'SVTEST-%'");

        shopId = insertShop(1);
        // is_featured=1：首页推荐那条落点只有推荐商品才走得到，否则该断言恒真（假绿）
        productId = insertProduct(1, 1);
    }

    private Long insertShop(Integer status) {
        Shop s = new Shop();
        s.setUserId(UID);
        s.setShopName("SVTEST-店铺");
        s.setStatus(status);
        shopMapper.insert(s);
        return s.getId();
    }

    private Long insertProduct(Integer status, Integer isFeatured) {
        Product p = new Product();
        p.setName("SVTEST-店内商品");
        p.setTitle("关闭店铺可见性测试");
        p.setBrief("关闭店铺可见性测试");
        p.setCategoryId(1L);
        p.setPrice(new BigDecimal("123.00"));
        p.setStock(5);
        p.setSales(0);
        p.setStatus(status);
        p.setIsFeatured(isFeatured);
        p.setShopId(shopId);
        productMapper.insert(p);
        return p.getId();
    }

    /** 仅改店铺状态，其余一概不动 */
    private void setShopStatus(int status) {
        jdbc.update("UPDATE dsm_shop SET status = ? WHERE id = ?", status, shopId);
    }

    private List<Long> storefrontIds() {
        return productSearchService.search(1, 200, new ProductQuery()).getRecords()
                .stream().map(ProductListVO::getId).toList();
    }

    private List<Long> featuredIds() {
        return productService.getFeatured(20).stream().map(ProductListVO::getId).toList();
    }

    private boolean detailAccessible() {
        try {
            productService.getDetail(productId);
            return true;
        } catch (BusinessException e) {
            assertEquals(404, e.getCode(), "详情不可见必须是 404，不是 403（403 会泄露 id 存在性）");
            return false;
        }
    }

    // ---------- 三个落点必须同进同退 ----------

    @Test
    void closingShop_hidesProductFromSearch_featured_andDetail_thenReopeningRestores() {
        // 截面 1：店铺开通 —— 三处都应可见。**这是对照组**，缺了它，
        // "全都查不到"会伪装成"过滤生效"（§10 附注要求的正向对照）
        assertTrue(storefrontIds().contains(productId), "店铺开通时商品应在列表可见");
        assertTrue(featuredIds().contains(productId), "店铺开通时商品应在首页推荐可见");
        assertTrue(detailAccessible(), "店铺开通时商品详情应可访问");

        // 截面 2：管理员驳回/关闭店铺 —— 三处必须同时消失
        setShopStatus(2);
        assertFalse(storefrontIds().contains(productId), "店铺已关闭，商品仍出现在列表（§4.8 落点 1 漏接）");
        assertFalse(featuredIds().contains(productId), "店铺已关闭，商品仍出现在首页推荐（§4.8 落点 2 漏接）");
        assertFalse(detailAccessible(), "店铺已关闭，商品详情仍可访问（§4.8 落点 3 漏接）");

        // 截面 3：店铺重新开通 —— 恢复可见。没有这一步，前面两条也可能是"这批数据本来就查不到"
        setShopStatus(1);
        assertTrue(storefrontIds().contains(productId), "店铺重新开通后商品应恢复可见");
        assertTrue(featuredIds().contains(productId), "店铺重新开通后推荐位应恢复");
        assertTrue(detailAccessible(), "店铺重新开通后详情应恢复");
    }

    // ---------- 判据不得溢出到管理端 / 商家侧 ----------

    @Test
    void closedShop_productsStillVisibleToAdmin() {
        setShopStatus(2);

        // 管理端必须照常看得到 —— 否则平台无法处理已关闭店铺的遗留商品。
        // 把判据误用到 searchAllStatus 上，故障形态是"后台商品列表静默少了一批"，不报错。
        List<Long> admin = productSearchService.searchAllStatus(1, 200, new ProductQuery()).getRecords()
                .stream().map(ProductListVO::getId).toList();
        assertTrue(admin.contains(productId), "店铺关闭后，管理端仍应看得到其商品");
    }

    // ---------- 平台自营不受店铺规则影响（E4）----------

    @Test
    void selfOperated_productsUnaffectedByShopVisibility() {
        Product selfOp = new Product();
        selfOp.setName("SVTEST-自营商品");
        selfOp.setTitle("自营");
        selfOp.setBrief("自营");
        selfOp.setCategoryId(1L);
        selfOp.setPrice(new BigDecimal("88.00"));
        selfOp.setStock(3);
        selfOp.setSales(0);
        selfOp.setStatus(1);
        selfOp.setIsFeatured(1);
        selfOp.setShopId(null);
        productMapper.insert(selfOp);

        setShopStatus(2); // 有个店铺被关了，但自营商品与店铺无关

        assertTrue(storefrontIds().contains(selfOp.getId()), "平台自营商品不受店铺可见性规则影响");
        assertTrue(featuredIds().contains(selfOp.getId()), "平台自营商品应照常出现在推荐位");
        assertEquals(selfOp.getId(), productService.getDetail(selfOp.getId()).getId());
    }

    // ---------- 商品自身下架仍须 404（判据合并后不得丢掉原语义）----------

    @Test
    void offShelfProduct_detailStill404_afterMergingStatusIntoPredicate() {
        setShopStatus(1);
        jdbc.update("UPDATE dsm_product SET status = 0 WHERE id = ?", productId);

        // getDetail 原先那句 product.getStatus() != 1 已并入判据。这条盯的是合并动作有没有
        // 把"下架商品详情不可访问"这条既有语义弄丢 —— 丢了不会有任何测试报错，只会静默放开
        assertFalse(detailAccessible(), "商品自身已下架，详情仍应 404");
    }

    // ---------- 分页 total 与可见行数一致（口径不得只改 records 不改 count）----------

    @Test
    void closingShop_totalCount_shrinksWithVisibleRows() {
        int before = (int) productSearchService.search(1, 200, new ProductQuery()).getTotal();
        setShopStatus(2);
        PageResult<ProductListVO> after = productSearchService.search(1, 200, new ProductQuery());

        assertEquals(before - 1, after.getTotal(),
                "关闭店铺后 total 应减 1；total 没跟着变说明判据只进了 records 查询没进 count 查询");
        assertEquals(after.getTotal(), after.getRecords().size());
    }
}
