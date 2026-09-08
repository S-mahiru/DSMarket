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

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 商品全文检索集成测试（真实 PostgreSQL，独立 dsmarket_test 库）：
 * 验证 zhparser 中文分词检索真实命中、多词 AND、空结果 LIKE 兜底、状态过滤。
 */
@SpringBootTest
@ActiveProfiles("test")
class ProductSearchIntegrationTest {

    @Autowired
    private ProductSearchService productSearchService;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM dsm_product_sku WHERE product_id IN (SELECT id FROM dsm_product WHERE name LIKE 'ITEST-%')");
        jdbc.update("DELETE FROM dsm_product WHERE name LIKE 'ITEST-%'");
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
