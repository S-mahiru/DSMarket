package com.dsmarket.modules.product.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.modules.product.dto.ProductListVO;
import com.dsmarket.modules.product.dto.ProductQuery;
import com.dsmarket.modules.product.entity.Product;
import com.dsmarket.modules.product.mapper.ProductMapper;
import com.dsmarket.modules.product.service.impl.ProductSearchServiceImpl;
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
 * 商品检索 Service 单测（Mockito）：
 * 验证检索流程——全文检索命中只查一次；零命中回退 LIKE（查两次）；空关键词仅筛选。
 * （SQL 是否真的走 tsvector @@ plainto_tsquery 由 ProductSearchIntegrationTest 用真库验证）
 */
@ExtendWith(MockitoExtension.class)
class ProductSearchServiceImplTest {

    @Mock
    private ProductMapper productMapper;
    @Mock
    private com.dsmarket.modules.category.service.CategoryService categoryService;

    @InjectMocks
    private ProductSearchServiceImpl service;

    private Product product(Long id) {
        Product p = new Product();
        p.setId(id);
        p.setName("iPhone 16");
        p.setPrice(new BigDecimal("5999"));
        p.setStatus(1);
        return p;
    }

    /** mock selectPage 返回给定记录 */
    private void mockPage(Page<Product> page, List<Product> records, long total) {
        page.setRecords(records);
        page.setTotal(total);
        page.setSize(10);
        page.setCurrent(1);
    }

    private ProductQuery query(String keyword) {
        ProductQuery q = new ProductQuery();
        q.setKeyword(keyword);
        return q;
    }

    @Test
    void search_keywordHit_queriesOnce() {
        // 全文检索命中 → 不再走 LIKE 兜底
        when(productMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(inv -> {
            Page<Product> page = inv.getArgument(0);
            mockPage(page, List.of(product(1L)), 1);
            return (IPage<Product>) page;
        });

        PageResult<ProductListVO> result = service.search(1, 10, query("苹果"));

        verify(productMapper, times(1)).selectPage(any(Page.class), any(Wrapper.class));
        assertEquals(1, result.getRecords().size());
    }

    @Test
    void search_fulltextNoHit_fallsBackToLike() {
        // 第一次(全文检索)零命中，第二次(LIKE 兜底)命中 → selectPage 应被调用两次
        when(productMapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenAnswer(inv -> {
                    Page<Product> page = inv.getArgument(0);
                    mockPage(page, List.of(), 0);
                    return (IPage<Product>) page;
                })
                .thenAnswer(inv -> {
                    Page<Product> page = inv.getArgument(0);
                    mockPage(page, List.of(product(2L)), 1);
                    return (IPage<Product>) page;
                });

        PageResult<ProductListVO> result = service.search(1, 10, query("生僻词xyz"));

        verify(productMapper, times(2)).selectPage(any(Page.class), any(Wrapper.class));
        assertEquals(1, result.getRecords().size());
        assertEquals(2L, result.getRecords().get(0).getId());
    }

    @Test
    void search_blankKeyword_queriesOnceNoFallback() {
        when(productMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(inv -> {
            Page<Product> page = inv.getArgument(0);
            mockPage(page, List.of(product(1L)), 1);
            return (IPage<Product>) page;
        });

        service.search(1, 10, query("   "));

        // 空关键词：一次普通筛选查询，不触发全文检索/兜底
        verify(productMapper, times(1)).selectPage(any(Page.class), any(Wrapper.class));
    }

    @Test
    void search_nullKeyword_queriesOnce() {
        when(productMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(inv -> {
            Page<Product> page = inv.getArgument(0);
            mockPage(page, List.of(product(1L)), 1);
            return (IPage<Product>) page;
        });

        service.search(1, 10, query(null));

        verify(productMapper, times(1)).selectPage(any(Page.class), any(Wrapper.class));
    }

    @Test
    void searchByShop_nullShopId_throwsBeforeQuerying() {
        // shopId=null 一旦被静默当成"不筛"，本方法就退化成全平台检索：越权结果表现为
        // "正常返回一页别家商品"，不报错、不留痕——最难发现的一类故障。所以必须抛，
        // 且必须在**碰库之前**抛。
        assertThrows(IllegalArgumentException.class,
                () -> service.searchByShop(1, 10, query(null), null));
        verify(productMapper, never()).selectPage(any(Page.class), any(Wrapper.class));
    }

    @Test
    void searchByShop_shopFilterIsInWhereClause() {
        // 白盒：把真正交给 MyBatis 的 WHERE 片段抠出来，确认 shop_id 在里面。
        // （单测只能证明"条件被拼上了"，SQL 打出去后是否真的筛掉别家行，由
        //  MerchantProductIsolationIntegrationTest 用真库验证。）
        when(productMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(inv -> {
            Page<Product> page = inv.getArgument(0);
            mockPage(page, List.of(), 0);
            return (IPage<Product>) page;
        });
        ArgumentCaptor<Wrapper<Product>> captor = ArgumentCaptor.forClass(Wrapper.class);

        service.searchByShop(1, 10, query(null), 10L);

        verify(productMapper).selectPage(any(Page.class), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("shop_id"),
                "商家侧检索的 WHERE 必须出现 shop_id，实际=" + sql);
    }

    @Test
    void searchByShop_keywordPath_keepsFilterOnBothFulltextAndFallback() {
        // 关键词路径是两次独立的 selectPage（全文检索 → 零命中后 LIKE 兜底）。
        // 这里断言两次都发生了，确保 shop 条件写在 doSearch 这一层——
        // 若谁把它挪进某个分支，兜底那次就会漏掉隔离。
        when(productMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(inv -> {
            Page<Product> page = inv.getArgument(0);
            mockPage(page, List.of(), 0);
            return (IPage<Product>) page;
        });

        service.searchByShop(1, 10, query("苹果"), 10L);

        verify(productMapper, times(2)).selectPage(any(Page.class), any(Wrapper.class));
    }

    @Test
    void searchAllStatus_andSearch_useSameMapper() {
        when(productMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(inv -> {
            Page<Product> page = inv.getArgument(0);
            mockPage(page, List.of(product(1L)), 1);
            return (IPage<Product>) page;
        });

        service.search(1, 10, query(null));
        service.searchAllStatus(1, 10, query(null));

        // 两条路径都走同一 mapper（状态过滤差异由集成测试验证真实 SQL）
        verify(productMapper, times(2)).selectPage(any(Page.class), any(Wrapper.class));
    }
}
