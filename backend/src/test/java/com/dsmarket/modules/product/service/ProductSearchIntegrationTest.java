package com.dsmarket.modules.product.service;

import com.dsmarket.common.domain.PageResult;
import com.dsmarket.modules.product.dto.ProductListVO;
import com.dsmarket.modules.product.dto.ProductQuery;
import com.dsmarket.modules.product.entity.Product;
import com.dsmarket.modules.product.mapper.ProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 商品全文检索集成测试（真实 PostgreSQL，独立 dsmarket_test 库）：
 * 验证 zhparser 中文分词检索真实命中、多词 AND、空结果 LIKE 兜底、状态过滤。
 *
 * <p>本类的多条断言是「恰好命中 N 条」，因此必须由测试自己掌握<b>全部</b>夹具 ——
 * 见 {@link #cleanSlate()}。{@code @Transactional} 是它的前提：清表随测试回滚，
 * 库里数据不会真的少掉。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductSearchIntegrationTest {

    @Autowired
    private ProductSearchService productSearchService;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 清空商品表，让每个用例只看见自己插入的夹具。
     *
     * <p>为什么要清<b>整表</b>而不是只删自己的 {@code ITEST-%}：
     * V3 种子商品 iPhone 16 的 {@code brief = '最新款苹果手机，性能怪兽'} 同时命中
     * 本类三个关键词（{@code 苹果} / {@code 手机} / 子串 {@code 果手}），
     * 一条数据就能把三条「恰好 1 条」的断言全部打成 2 条。
     * 全新初始化的库上必红（既有库上只因演示商品早被删掉才侥幸是绿的）。
     *
     * <p>原写法只删 {@code name LIKE 'ITEST-%'}，还有第二个坑：订单模块的测试
     * 也在用 {@code ITEST-} 前缀（如 {@code ITEST-并发防超卖}），会被本类误删。
     *
     * <p>删除与插入都在测试事务内，跑完自动回滚 —— 对库本身零副作用。
     */
    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM dsm_product");
    }

    private Long insertProduct(String name, String brief, Integer status) {
        Product p = new Product();
        p.setName(name);
        p.setTitle("测试卖点");
        p.setBrief(brief);
        p.setCategoryId(1L);
        p.setPrice(new BigDecimal("99.00"));
        p.setStock(10);
        p.setSales(5);
        p.setStatus(status);
        productMapper.insert(p);
        return p.getId();
    }

    private ProductQuery kw(String keyword) {
        ProductQuery q = new ProductQuery();
        q.setKeyword(keyword);
        return q;
    }

    @Test
    void search_chineseKeyword_hitsBySegmentation() {
        insertProduct("ITEST-苹果手机", "旗舰机型", 1);
        insertProduct("ITEST-华为手机", "国产旗舰", 1);

        // "苹果" 是 "苹果手机" 的分词结果，应命中
        PageResult<ProductListVO> result = productSearchService.search(1, 10, kw("苹果"));
        assertEquals(1, result.getRecords().size());
        assertTrue(result.getRecords().get(0).getName().contains("苹果"));
    }

    @Test
    void search_multiWord_andSemantics() {
        insertProduct("ITEST-苹果手机", "水果旗舰", 1);
        insertProduct("ITEST-苹果耳机", "蓝牙", 1);

        // plainto_tsquery 空格=AND：只有同时含"苹果"和"手机"的命中
        PageResult<ProductListVO> result = productSearchService.search(1, 10, kw("苹果 手机"));
        assertEquals(1, result.getRecords().size());
        assertEquals("ITEST-苹果手机", result.getRecords().get(0).getName());
    }

    @Test
    void search_noHit_fallsBackToLike() {
        // 中文关键词可分词命中；构造一个 zhparser 切不出、但 LIKE 能模糊命中的词
        insertProduct("ITEST-苹果手机", "旗舰机型", 1);
        // "果手" 不是合法词，zhparser 不切词 → 全文检索零命中 → LIKE 兜底应命中"苹果手机"
        PageResult<ProductListVO> result = productSearchService.search(1, 10, kw("果手"));
        assertEquals(1, result.getRecords().size());
    }

    @Test
    void search_offShelfProduct_excludedInStorefront() {
        Long id = insertProduct("ITEST-下架商品", "已下架", 0);

        // 商城侧：下架商品不应被检索到
        PageResult<ProductListVO> result = productSearchService.search(1, 10, kw("下架商品"));
        assertTrue(result.getRecords().stream().noneMatch(v -> v.getId().equals(id)));

        // 管理端：不过滤状态，应能检索到
        PageResult<ProductListVO> admin = productSearchService.searchAllStatus(1, 10, kw("下架商品"));
        assertTrue(admin.getRecords().stream().anyMatch(v -> v.getId().equals(id)));
    }

    @Test
    void search_blankKeyword_returnsAllOnSale() {
        insertProduct("ITEST-苹果手机", "旗舰机型", 1);
        PageResult<ProductListVO> result = productSearchService.search(1, 10, kw("   "));
        assertNotNull(result);
        assertTrue(result.getRecords().size() >= 1);
    }
}
