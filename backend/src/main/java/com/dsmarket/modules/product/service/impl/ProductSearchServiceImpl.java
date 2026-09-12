package com.dsmarket.modules.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.modules.category.service.CategoryService;
import com.dsmarket.modules.product.dto.ProductListVO;
import com.dsmarket.modules.product.dto.ProductQuery;
import com.dsmarket.modules.product.entity.Product;
import com.dsmarket.modules.product.mapper.ProductMapper;
import com.dsmarket.modules.product.service.ProductSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 商品检索的 PostgreSQL 全文检索实现。
 *
 * <p>检索链路：关键词 → {@code to_tsvector('zhparser_config', name||title||brief)
 * @@ plainto_tsquery('zhparser_config', :kw)}，命中 GIN 索引 ft_search（V5 迁移创建）。
 * 中文经 zhparser 分词（如"苹果手机"切为"苹果"、"手机"），配合分类/品牌/价格筛选与排序。</p>
 *
 * <p>边界处理：关键词为空时不走全文检索（等同不过滤）；全文检索零命中时退回 LIKE 模糊匹配，
 * 避免 zhparser 对生僻词不切词导致"明明存在却搜不到"的体验回归。</p>
 */
@Service
@RequiredArgsConstructor
public class ProductSearchServiceImpl implements ProductSearchService {

    /** zhparser 中文分词配置名（V5__zhparser_fulltext.sql 创建） */
    private static final String CONFIG = "zhparser_config";
    /** 参与全文检索的商品列（与 ft_search 索引表达式保持一致） */
    private static final String TSVECTOR = "to_tsvector('" + CONFIG + "', "
            + "coalesce(name,'') || ' ' || coalesce(title,'') || ' ' || coalesce(brief,''))";

    private final ProductMapper productMapper;
    private final CategoryService categoryService;

    @Override
    public PageResult<ProductListVO> search(int page, int size, ProductQuery query) {
        return searchInternal(page, size, query, true, null);
    }

    @Override
    public PageResult<ProductListVO> searchAllStatus(int page, int size, ProductQuery query) {
        return searchInternal(page, size, query, false, null);
    }

    @Override
    public PageResult<ProductListVO> searchByShop(int page, int size, ProductQuery query, Long shopId) {
        // 这里**故意抛异常而不是把 null 当"不筛"**：null 一旦被静默接受，本方法就退化成
        // 全平台检索 —— 越权结果是"正常返回一页别家商品"，不报错、不留痕，最难发现。
        // shopId 只可能来自服务端解析，为 null 说明调用方写错了，应当立刻炸掉。
        if (shopId == null) {
            throw new IllegalArgumentException("searchByShop 的 shopId 不能为 null（会退化成全平台检索）");
        }
        return searchInternal(page, size, query, false, shopId);
    }

    /**
     * @param onlyOnSale true=商城侧只查上架（status=1）；false=管理端/商家侧不过滤状态
     * @param shopId     非 null=只查该店铺（商家侧）；null=不按店铺筛（商城/管理端）
     */
    private PageResult<ProductListVO> searchInternal(int page, int size, ProductQuery query, boolean onlyOnSale, Long shopId) {
        String keyword = query != null ? (query.getKeyword() == null ? null : query.getKeyword().trim()) : null;

        if (StringUtils.hasText(keyword)) {
            // 1) 先走全文检索（中文分词 + GIN 索引）
            PageResult<ProductListVO> ft = doSearch(page, size, query, true, onlyOnSale, shopId);
            if (!ft.getRecords().isEmpty()) {
                return ft;
            }
            // 2) 全文检索零命中 → LIKE 兜底（防生僻词/标点分词不中导致漏搜）
            return doSearch(page, size, query, false, onlyOnSale, shopId);
        }
        // 无关键词：普通筛选 + 排序（不走全文检索，保持与历史一致）
        return doSearch(page, size, query, null, onlyOnSale, shopId);
    }

    /**
     * @param useFulltext true=全文检索；false=LIKE 兜底；null=无关键词仅筛选
     * @param onlyOnSale  true=只查上架；false=不过滤状态
     * @param shopId      非 null=只查该店铺；null=不按店铺筛
     */
    private PageResult<ProductListVO> doSearch(int page, int size, ProductQuery query, Boolean useFulltext,
                                               boolean onlyOnSale, Long shopId) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        if (onlyOnSale) {
            wrapper.eq(Product::getStatus, 1);
        }
        // 店铺隔离。放在关键词/兜底分支**之前**是刻意的：它是**安全条件**而非筛选条件，
        // 必须无条件生效 —— 全文检索与 LIKE 兜底是两次独立的 doSearch 调用，隔离写在这里
        // 才能保证两条路径都带上它（写在某个分支里会漏掉另一条）。
        if (shopId != null) {
            wrapper.eq(Product::getShopId, shopId);
        }

        if (Boolean.TRUE.equals(useFulltext)) {
            String kw = query.getKeyword().trim();
            // plainto_tsquery 自动按 zhparser 切词，多个词用空格 = AND 关系；特殊字符被当作普通文本处理
            wrapper.apply(TSVECTOR + " @@ plainto_tsquery('" + CONFIG + "', {0})", kw);
        } else if (Boolean.FALSE.equals(useFulltext)) {
            String kw = query.getKeyword().trim();
            wrapper.and(w -> w.like(Product::getName, kw)
                    .or().like(Product::getTitle, kw)
                    .or().like(Product::getBrief, kw));
        }

        applyFilters(wrapper, query);
        applySort(wrapper, query.getSortBy(), query.getSortOrder());

        Page<Product> p = productMapper.selectPage(new Page<>(page, size), wrapper);
        List<ProductListVO> records = p.getRecords().stream().map(ProductListVO::from).toList();
        return PageResult.of(records, p.getTotal(), p.getCurrent(), p.getSize());
    }

    private void applyFilters(LambdaQueryWrapper<Product> wrapper, ProductQuery query) {
        if (query.getCategoryId() != null) {
            List<Long> ids = categoryService.getCategoryIdsIncludingDescendants(query.getCategoryId());
            if (!ids.isEmpty()) {
                wrapper.in(Product::getCategoryId, ids);
            }
        }
        if (StringUtils.hasText(query.getBrand())) {
            wrapper.eq(Product::getBrand, query.getBrand());
        }
        if (query.getMinPrice() != null) {
            wrapper.ge(Product::getPrice, query.getMinPrice());
        }
        if (query.getMaxPrice() != null) {
            wrapper.le(Product::getPrice, query.getMaxPrice());
        }
    }

    private void applySort(LambdaQueryWrapper<Product> wrapper, String sortBy, String sortOrder) {
        String by = StringUtils.hasText(sortBy) ? sortBy : "sales";
        boolean asc = "asc".equalsIgnoreCase(sortOrder);
        switch (by) {
            case "price":
                wrapper.orderBy(true, asc, Product::getPrice);
                break;
            case "createdAt":
                wrapper.orderBy(true, asc, Product::getCreatedAt);
                break;
            default:
                wrapper.orderByDesc(Product::getSales);
        }
    }
}
