package com.dsmarket.modules.shop.controller;

import com.dsmarket.modules.product.dto.ProductDetailVO;
import com.dsmarket.modules.product.dto.ProductListVO;
import com.dsmarket.modules.product.dto.ProductQuery;
import com.dsmarket.modules.product.entity.Product;
import com.dsmarket.modules.product.mapper.ProductMapper;
import com.dsmarket.modules.product.service.ProductService;
import com.dsmarket.modules.shop.dto.ShopPublicVO;
import com.dsmarket.modules.shop.entity.Shop;
import com.dsmarket.modules.shop.mapper.ShopMapper;
import com.dsmarket.modules.shop.service.ShopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公开店铺接口与自营专区（REQ-20260913 §4.6 / §4.7 / §6.3）的集成测试。
 *
 * <p>覆盖三件在代码里看不出来的事：① 公开 DTO <b>结构上</b>只有 4 个字段；
 * ② {@code /api/v1/shops/**} 放行了，而商家侧单数前缀<b>没被顺手放行</b>；
 * ③ 自营商品与店铺商品两个口径<b>互不串味</b>。</p>
 *
 * <p>数据以 {@code SHTEST-} 前缀命名，{@code @BeforeEach} 按前缀清理。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicShopApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ShopService shopService;
    @Autowired
    private ProductService productService;
    @Autowired
    private ShopMapper shopMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private Long shop1;
    private Long shop2;
    private Long p1;
    private Long p2;
    private Long pSelf;

    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM dsm_product_sku WHERE product_id IN (SELECT id FROM dsm_product WHERE name LIKE 'SHTEST-%')");
        jdbc.update("DELETE FROM dsm_product WHERE name LIKE 'SHTEST-%'");
        jdbc.update("DELETE FROM dsm_shop WHERE shop_name LIKE 'SHTEST-%'");

        shop1 = insertShop("SHTEST-甲店", 1);
        shop2 = insertShop("SHTEST-乙店", 1);
        p1 = insertProduct("SHTEST-甲店商品", shop1);
        p2 = insertProduct("SHTEST-乙店商品", shop2);
        pSelf = insertProduct("SHTEST-自营商品", null);
    }

    // ---------------------- 夹具 ----------------------

    private Long insertShop(String name, int status) {
        Shop s = new Shop();
        s.setUserId(940000L + shopMapper.selectCount(null));
        s.setShopName(name);
        // 显式给 logo 赋值：项目全局 NON_NULL 序列化，logo 为 null 时字段会整个消失，
        // 那样"响应里只有 4 个字段"的断言会因为少一个字段而失去区分度
        s.setLogo("/uploads/shtest-logo.png");
        s.setDescription("SHTEST 测试店铺");
        s.setStatus(status);
        shopMapper.insert(s);
        return s.getId();
    }

    private Long insertProduct(String name, Long shopId) {
        Product p = new Product();
        p.setName(name);
        p.setTitle(name);
        p.setBrief(name);
        p.setCategoryId(1L);
        p.setPrice(new BigDecimal("66.00"));
        p.setStock(5);
        p.setSales(0);
        p.setStatus(1);
        p.setIsFeatured(0);
        p.setShopId(shopId);
        productMapper.insert(p);
        return p.getId();
    }

    private List<Long> idsOf(List<ProductListVO> records) {
        return records.stream().map(ProductListVO::getId).toList();
    }

    // ---------------------- 公开 DTO 只暴露 4 个字段 ----------------------

    /**
     * <b>结构性</b>探针：直接数 {@link ShopPublicVO} 声明了哪些字段。
     *
     * <p><b>为什么不用"响应体里没有 userId"来断言</b>：项目全局配了
     * {@code spring.jackson.default-property-inclusion=non_null}，字段值为 null 时会被整个省略 ——
     * 于是哪怕真的加了 {@code userId}，只要那条数据恰好没填值，响应里同样"没有 userId"，
     * 一条只看响应体的断言会<b>照样通过</b>。反射看的是结构，与运行时的值无关。</p>
     *
     * <p>这条会在有人往 VO 里加字段时变红。红了先别急着改期望值，先问：
     * 这个字段能给<b>匿名访客</b>看吗。</p>
     */
    @Test
    void shopPublicVO_declaresExactlyFourFields() {
        Set<String> names = Arrays.stream(ShopPublicVO.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of("id", "shopName", "logo", "description"), names,
                "公开店铺 VO 的字段集合变了。userId / status / statusName / auditRemark / ownerUsername "
                        + "一个都不能进来 —— 这个接口匿名可访问。");
    }

    /** 线上实际发出的 JSON：4 个字段都在，且不含任何敏感键 */
    @Test
    void publicShopEndpoint_returnsFourFields_andNoSensitiveKey() throws Exception {
        mockMvc.perform(get("/api/v1/shops/" + shop1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(shop1))
                .andExpect(jsonPath("$.data.shopName").value("SHTEST-甲店"))
                .andExpect(jsonPath("$.data.logo").value("/uploads/shtest-logo.png"))
                .andExpect(jsonPath("$.data.description").value("SHTEST 测试店铺"))
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.status").doesNotExist())
                .andExpect(jsonPath("$.data.statusName").doesNotExist())
                .andExpect(jsonPath("$.data.auditRemark").doesNotExist())
                .andExpect(jsonPath("$.data.ownerUsername").doesNotExist());
    }

    // ---------------------- 路径前缀：复数放行、单数不许被顺手放行 ----------------------

    /**
     * 游客能读公开店铺 —— 证明 {@code /api/v1/shops/**} 确实进了 permitAll。
     *
     * <p>没有这条正向对照，"打不开"会被读成"鉴权配对了"。</p>
     */
    @Test
    void anonymous_canReadPublicShop() throws Exception {
        mockMvc.perform(get("/api/v1/shops/" + shop1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * <b>商家侧单数前缀必须仍然要登录</b>。
     *
     * <p>这是"公开路径要用复数"这条决定的守门用例：若有人图省事把 permitAll 写成
     * {@code /api/v1/shop/**}（单数），{@code POST /shop/apply} 就变成<b>匿名可提交入驻申请</b>，
     * 而这条接口看起来照常工作，谁也不会察觉。删掉本用例，该错误可以一路走到线上。</p>
     */
    @Test
    void anonymous_cannotSubmitShopApplication_pluralPrefixDidNotOpenTheSingularOne() throws Exception {
        mockMvc.perform(post("/api/v1/shop/apply").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get("/api/v1/shop/mine"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------- 不可见店铺一律 404 ----------------------

    @Test
    void closedShop_is404_onBothEndpoints_notAnEmptyList() throws Exception {
        jdbc.update("UPDATE dsm_shop SET status = 2 WHERE id = ?", shop1);

        mockMvc.perform(get("/api/v1/shops/" + shop1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        // 商品接口若先查商品再校验店铺，这里会返回 200 + 空数组，页面渲染成"这家店暂时没货"
        mockMvc.perform(get("/api/v1/shops/" + shop1 + "/products"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void pendingAndMissingShop_areAlso404() {
        jdbc.update("UPDATE dsm_shop SET status = 0 WHERE id = ?", shop2);
        assertThrows(Exception.class, () -> shopService.getPublicShop(shop2));
        assertThrows(Exception.class, () -> shopService.getPublicShop(99999999L));
    }

    // ---------------------- 两个口径互不串味 ----------------------

    @Test
    void shopProducts_containOnlyThatShop() {
        List<Long> ids = idsOf(productService.publicShopPage(1, 200, new ProductQuery(), shop1).getRecords());

        assertTrue(ids.contains(p1), "本店商品应在结果里");
        assertFalse(ids.contains(p2), "别家店铺的商品不得出现");
        assertFalse(ids.contains(pSelf), "平台自营商品不属于任何店铺，不得出现在店铺页");
    }

    @Test
    void selfOperated_containsOnlyShopIdNullProducts() {
        List<Long> ids = idsOf(productService.selfOperatedPage(1, 200, new ProductQuery()).getRecords());

        assertTrue(ids.contains(pSelf), "自营商品应在自营专区里");
        assertFalse(ids.contains(p1), "店铺商品不得混进自营专区");
        assertFalse(ids.contains(p2), "店铺商品不得混进自营专区");
    }

    /** 自营专区里的商品若所属店铺被关闭，它不受影响（E4）—— 因为它压根不属于任何店铺 */
    @Test
    void selfOperated_isUnaffectedByAnyShopBeingClosed() {
        jdbc.update("UPDATE dsm_shop SET status = 2 WHERE id IN (?, ?)", shop1, shop2);

        assertTrue(idsOf(productService.selfOperatedPage(1, 200, new ProductQuery()).getRecords()).contains(pSelf));
    }

    // ---------------------- 详情的商家行 ----------------------

    @Test
    void productDetail_carriesShopIdAndName_forMerchantProduct() throws Exception {
        ProductDetailVO vo = productService.getDetail(p1);
        assertEquals(shop1, vo.getShopId());
        assertEquals("SHTEST-甲店", vo.getShopName());

        mockMvc.perform(get("/api/v1/products/" + p1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shopId").value(shop1))
                .andExpect(jsonPath("$.data.shopName").value("SHTEST-甲店"));
    }

    /**
     * 自营商品：{@code shopId} 为 null —— 这是前端"渲染「平台自营」并链到自营专区"的判据。
     *
     * <p>注意 JSON 里这个键是<b>整个不出现</b>的（全局 NON_NULL），不是 {@code "shopId": null}。
     * 前端写 {@code data.shopId == null} 两者皆可命中，但 TS 类型要按可选字段声明。</p>
     */
    @Test
    void productDetail_hasNullShopId_forSelfOperatedProduct() throws Exception {
        assertNull(productService.getDetail(pSelf).getShopId());

        mockMvc.perform(get("/api/v1/products/" + pSelf))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shopId").doesNotExist())
                .andExpect(jsonPath("$.data.shopName").doesNotExist());
    }
}
