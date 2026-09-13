package com.dsmarket.modules.category.service;

import com.dsmarket.modules.category.dto.CategoryNodeVO;
import com.dsmarket.modules.category.entity.Category;
import com.dsmarket.modules.category.mapper.CategoryMapper;
import com.dsmarket.modules.product.dto.ProductListVO;
import com.dsmarket.modules.product.dto.ProductQuery;
import com.dsmarket.modules.product.entity.Product;
import com.dsmarket.modules.product.mapper.ProductMapper;
import com.dsmarket.modules.product.service.ProductSearchService;
import com.dsmarket.modules.shop.entity.Shop;
import com.dsmarket.modules.shop.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分类商品计数（REQ-20260913 §4.9）的集成测试。
 *
 * <p>本类的核心不是"数对了没有"，而是<b>徽标与点击结果恒等</b>。两者的父子展开口径来自
 * 两个不同的地方 —— 点击走 {@code CategoryServiceImpl.getCategoryIdsIncludingDescendants}
 * （整表、不过滤分类 status），装配走本 REQ 新写的递归 —— 只要两处口径不一致，
 * 页面就会出现「胶囊标 2、点进去 3 条」，而<b>两端都不报错</b>。</p>
 *
 * <p>数据以 {@code CPVTEST-} 前缀命名，{@code @BeforeEach} 按前缀清理。
 * 分类树形固定为：A(启用) → B(<b>禁用</b>) → C(启用)，另有 A 的启用子节点 D。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class CategoryProductCountIntegrationTest {

    @Autowired
    private CategoryService categoryService;
    @Autowired
    private ProductSearchService productSearchService;
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private ShopMapper shopMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private Long catA;
    private Long catB;
    private Long catC;
    private Long catD;

    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM dsm_product_sku WHERE product_id IN (SELECT id FROM dsm_product WHERE name LIKE 'CPVTEST-%')");
        jdbc.update("DELETE FROM dsm_product WHERE name LIKE 'CPVTEST-%'");
        jdbc.update("DELETE FROM dsm_category WHERE name LIKE 'CPVTEST-%'");
        jdbc.update("DELETE FROM dsm_shop WHERE shop_name LIKE 'CPVTEST-%'");

        catA = insertCategory("CPVTEST-A", 0L, 1, 1);
        catB = insertCategory("CPVTEST-B", catA, 2, 0);   // ← 禁用。本类全部张力都来自这一行
        catC = insertCategory("CPVTEST-C", catB, 3, 1);
        catD = insertCategory("CPVTEST-D", catA, 2, 1);
    }

    // ---------------------- 夹具 ----------------------

    private Long insertCategory(String name, Long parentId, int level, int status) {
        Category c = new Category();
        c.setName(name);
        c.setParentId(parentId);
        c.setLevel(level);
        c.setSortOrder(0);
        c.setStatus(status);
        categoryMapper.insert(c);
        return c.getId();
    }

    /** 平台自营商品（shop_id IS NULL），恒公开可见 */
    private Long insertProduct(String name, Long categoryId) {
        Product p = new Product();
        p.setName(name);
        p.setTitle(name);
        p.setBrief(name);
        p.setCategoryId(categoryId);
        p.setPrice(new BigDecimal("10.00"));
        p.setStock(1);
        p.setSales(0);
        p.setStatus(1);
        p.setIsFeatured(0);
        p.setShopId(null);
        productMapper.insert(p);
        return p.getId();
    }

    /** 前台树里每个节点的 productCount，按 id 索引。不在树里的分类（如被禁用的 B）不会出现在结果中 */
    private Map<Long, Long> countsById() {
        Map<Long, Long> map = new HashMap<>();
        collect(categoryService.getTree(), map);
        return map;
    }

    private void collect(List<CategoryNodeVO> nodes, Map<Long, Long> map) {
        for (CategoryNodeVO n : nodes) {
            map.put(n.getId(), n.getProductCount());
            collect(n.getChildren(), map);
        }
    }

    private long clickTotal(Long categoryId) {
        ProductQuery q = new ProductQuery();
        q.setCategoryId(categoryId);
        return productSearchService.search(1, 200, q).getTotal();
    }

    // ---------------------- E11：全案最容易写错的一处 ----------------------

    /**
     * 被禁用的中间分类<b>不在前台树里</b>，但它挂着的商品<b>仍会被 categoryId 筛中</b>。
     *
     * <p>所以祖先的徽标必须把它算进去 —— 只有"整表父子关系"口径做得到。
     * 若实现写成"对启用树后序累加"，本用例的 A 会得到 <b>1</b>（只有直挂的 P2），
     * 而点击 {@code categoryId=A} 实际返回 <b>2</b> 条。</p>
     */
    @Test
    void disabledIntermediateCategory_itsDescendantsStillCountTowardTheAncestor() {
        insertProduct("CPVTEST-挂在C下", catC);
        insertProduct("CPVTEST-直挂A", catA);

        Map<Long, Long> counts = countsById();

        assertEquals(2L, counts.get(catA),
                "A 的计数必须包含「经禁用分类 B 挂在其子树下」的 C 类商品 —— 这是整表口径，不是前台树口径");
        assertEquals(1L, counts.get(catC), "C 自己直挂 1 件");
        assertEquals(0L, counts.get(catD), "D 无商品，应显示 0 而不是被隐藏（§12.2 A2）");
    }

    /**
     * 记录上一用例依赖的树形事实：B 被禁用 → C 被<b>提升为根节点</b>，
     * 于是 C 从 A 的 {@code children} 里整棵消失。
     *
     * <p>钉住这一点，是因为它就是"按树累加会少一整棵子树"的成因；
     * 哪天 {@code buildTree} 改了提升行为，这里会先红，比等到页面上出错早得多。</p>
     */
    @Test
    void disabledIntermediateCategory_isAbsentFromTree_andItsChildIsPromotedToRoot() {
        insertProduct("CPVTEST-挂在C下", catC);

        Map<Long, Long> counts = countsById();

        assertFalse(counts.containsKey(catB), "被禁用的分类不应出现在前台树里");
        assertTrue(counts.containsKey(catC), "C 应被提升为根节点，从而仍出现在树里");
        assertFalse(descendantIds(catA).contains(catC), "C 已不在 A 的子树内 —— 这正是按树累加会漏掉它的原因");
    }

    private List<Long> descendantIds(Long rootId) {
        for (CategoryNodeVO root : categoryService.getTree()) {
            if (root.getId().equals(rootId)) {
                Map<Long, Long> sub = new HashMap<>();
                collect(root.getChildren(), sub);
                return List.copyOf(sub.keySet());
            }
        }
        return List.of();
    }

    // ---------------------- 不变量：徽标 == 点击结果 ----------------------

    /**
     * 本 REQ 对计数的<b>唯一硬要求</b>：胶囊上的数就是点进去的条数。
     *
     * <p>这条比"某个数等于 2"更强 —— 它不写死期望值，而是直接比较两个独立实现的结果，
     * 所以父子关系怎么变都不会假绿。</p>
     */
    @Test
    void badgeValue_equalsWhatClickingItReturns_forEveryTreeNode() {
        insertProduct("CPVTEST-挂在C下", catC);
        insertProduct("CPVTEST-直挂A", catA);
        insertProduct("CPVTEST-直挂D", catD);

        Map<Long, Long> counts = countsById();
        for (Long id : List.of(catA, catC, catD)) {
            long badge = counts.get(id);
            long clicked = clickTotal(id);
            assertEquals(clicked, badge,
                    "分类 " + id + " 的徽标是 " + badge + "，点进去却是 " + clicked + " 条");
        }
    }

    /**
     * 被禁用的分类自身不在树里，但<b>按 URL 直接筛它仍应返回其子树商品</b>
     * （列表页不过滤分类 status）。这条不是本 REQ 引入的行为，是既有契约，
     * 写下来是为了说明上一条为什么不比较 B：B 根本没有徽标可比。
     */
    @Test
    void disabledCategory_isStillFilterableByUrl_butHasNoBadge() {
        insertProduct("CPVTEST-挂在C下", catC);

        assertEquals(1L, clickTotal(catB), "直接按 categoryId=B 筛，应返回 B 子树（含 C）的商品");
        assertFalse(countsById().containsKey(catB), "但 B 没有徽标，因为它根本不在前台树上");
    }

    // ---------------------- 与店铺可见性联动（§4.8 落点 4）----------------------

    /**
     * 关闭店铺后，其商品的分类计数必须同步减少。
     *
     * <p>计数是这个 REQ 里唯一一条 SQL 与其它三个落点形态完全不同的路径，
     * 漏接它的表现是"商品已经看不见了，胶囊上的数字却没变"。</p>
     */
    @Test
    void closingShop_decrementsCategoryCount_byExactlyOne() {
        Shop s = new Shop();
        s.setUserId(930001L);
        s.setShopName("CPVTEST-店铺");
        s.setStatus(1);
        shopMapper.insert(s);

        Product p = new Product();
        p.setName("CPVTEST-店内商品");
        p.setTitle("x");
        p.setBrief("x");
        p.setCategoryId(catD);
        p.setPrice(new BigDecimal("10.00"));
        p.setStock(1);
        p.setSales(0);
        p.setStatus(1);
        p.setIsFeatured(0);
        p.setShopId(s.getId());
        productMapper.insert(p);

        assertEquals(1L, countsById().get(catD), "店铺开通时计入");

        jdbc.update("UPDATE dsm_shop SET status = 2 WHERE id = ?", s.getId());

        assertEquals(0L, countsById().get(catD),
                "店铺已关闭，分类计数仍算着它的商品（§4.8 落点 4 漏接）——"
                        + "页面表现是「胶囊标 1、点进去 0 条」");
        assertEquals(0L, clickTotal(catD), "点击结果与徽标必须同时归零");
    }
}
