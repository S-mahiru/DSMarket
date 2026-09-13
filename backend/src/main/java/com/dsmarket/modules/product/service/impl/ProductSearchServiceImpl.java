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
import com.dsmarket.modules.product.support.ProductVisibility;
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

    /**
     * 检索作用域：决定「查谁的商品」以及「过不过滤公开可见性」。
     *
     * <p><b>为什么收敛成一个对象而不是继续加布尔参数</b>：原先 {@code searchInternal(...)}
     * 已经吃 {@code (onlyOnSale, shopId)} 两个位置参数，再加"自营"就成三个相邻的布尔/长整型，
     * 调用处写反一个（例如把 {@code onlyOnSale} 传成 {@code selfOperatedOnly}）**不会有任何编译错误**，
     * 故障形态是"某个入口静默查错了范围"。工厂方法把每种业务口径钉成一个有名字的调用。</p>
     *
     * <p>各个工厂的取值组合即本 REQ 的核心契约，改这里等于改全站口径，务必对照 §4.8 的四个落点。</p>
     */
    private record Scope(boolean onlyOnSale, Long shopId, boolean selfOperatedOnly) {

        /** 商城侧全站：只上架 + 公开可见 */
        static Scope storefront() {
            return new Scope(true, null, false);
        }

        /** 管理端：**不过滤**状态（要看得见已下架），也不套店铺可见性 */
        static Scope admin() {
            return new Scope(false, null, false);
        }

        /** 商家侧：**不过滤**状态（商家要看得见自家已下架商品），限本店 */
        static Scope merchant(Long shopId) {
            return new Scope(false, shopId, false);
        }

        /** 前台店铺页：只上架 + 公开可见 + 限该店 */
        static Scope publicShop(Long shopId) {
            return new Scope(true, shopId, false);
        }

        /** 前台自营专区：只上架 + 公开可见，且 {@code shop_id IS NULL} */
        static Scope selfOperated() {
            return new Scope(true, null, true);
        }
    }

    @Override
    public PageResult<ProductListVO> search(int page, int size, ProductQuery query) {
        return searchInternal(page, size, query, Scope.storefront());
    }

    @Override
    public PageResult<ProductListVO> searchAllStatus(int page, int size, ProductQuery query) {
        return searchInternal(page, size, query, Scope.admin());
    }

    @Override
    public PageResult<ProductListVO> searchByShop(int page, int size, ProductQuery query, Long shopId) {
        // 这里**故意抛异常而不是把 null 当"不筛"**：null 一旦被静默接受，本方法就退化成
        // 全平台检索 —— 越权结果是"正常返回一页别家商品"，不报错、不留痕，最难发现。
        // shopId 只可能来自服务端解析，为 null 说明调用方写错了，应当立刻炸掉。
        if (shopId == null) {
            throw new IllegalArgumentException("searchByShop 的 shopId 不能为 null（会退化成全平台检索）");
        }
        return searchInternal(page, size, query, Scope.merchant(shopId));
    }

    @Override
    public PageResult<ProductListVO> searchPublicByShop(int page, int size, ProductQuery query, Long shopId) {
        // 同 searchByShop：null 会退化成"全站商城检索"，故障是"店铺页列出全平台商品"，必须立刻炸掉
        if (shopId == null) {
            throw new IllegalArgumentException("searchPublicByShop 的 shopId 不能为 null（会退化成全站商城检索）");
        }
        return searchInternal(page, size, query, Scope.publicShop(shopId));
    }

    @Override
    public PageResult<ProductListVO> searchSelfOperated(int page, int size, ProductQuery query) {
        return searchInternal(page, size, query, Scope.selfOperated());
    }

    private PageResult<ProductListVO> searchInternal(int page, int size, ProductQuery query, Scope scope) {
        String keyword = query != null ? (query.getKeyword() == null ? null : query.getKeyword().trim()) : null;

        if (StringUtils.hasText(keyword)) {
            // 1) 先走全文检索（中文分词 + GIN 索引）
            PageResult<ProductListVO> ft = doSearch(page, size, query, true, scope);
            if (!ft.getRecords().isEmpty()) {
                return ft;
            }
            // 2) 全文检索零命中 → LIKE 兜底（防生僻词/标点分词不中导致漏搜）
            return doSearch(page, size, query, false, scope);
        }
        // 无关键词：普通筛选 + 排序（不走全文检索，保持与历史一致）
        return doSearch(page, size, query, null, scope);
    }

    /**
     * @param useFulltext true=全文检索；false=LIKE 兜底；null=无关键词仅筛选
     * @param scope       检索作用域（决定状态过滤、店铺可见性、店铺隔离）
     */
    private PageResult<ProductListVO> doSearch(int page, int size, ProductQuery query, Boolean useFulltext,
                                               Scope scope) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        if (scope.onlyOnSale()) {
            wrapper.eq(Product::getStatus, 1);
            // 商城侧再叠加「公开可见」判据（REQ-20260913 §4.8 落点 1）：所属店铺被关闭/驳回/
            // 软删的商品，即使自身 status=1 也不再对 C 端可见。
            // **挂在 onlyOnSale 分支内是刻意的**：管理端 searchAllStatus 与商家侧 searchByShop
            // 用的都是 Scope.admin()/Scope.merchant()（onlyOnSale=false），必须照常看得到这些商品
            // （商家要能管理自家已关闭店铺的商品）。
            ProductVisibility.apply(wrapper);
        }
        // 店铺隔离。放在关键词/兜底分支**之前**是刻意的：它是**安全条件**而非筛选条件，
        // 必须无条件生效 —— 全文检索与 LIKE 兜底是两次独立的 doSearch 调用，隔离写在这里
        // 才能保证两条路径都带上它（写在某个分支里会漏掉另一条）。
        if (scope.shopId() != null) {
            wrapper.eq(Product::getShopId, scope.shopId());
        } else if (scope.selfOperatedOnly()) {
            // 自营 = shop_id IS NULL。**不能用 eq(null)**：SQL 里 NULL = NULL 恒为 UNKNOWN，
            // 会静默返回空列表，看着像"自营专区没有商品"而不是写错了。
            wrapper.isNull(Product::getShopId);
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
