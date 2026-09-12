package com.dsmarket.modules.product.service;

import com.dsmarket.common.domain.PageResult;
import com.dsmarket.modules.product.dto.ProductListVO;
import com.dsmarket.modules.product.dto.ProductQuery;

/**
 * 商品检索服务接口。
 *
 * <p>与具体检索引擎解耦：当前实现为 PostgreSQL 全文检索（zhparser 中文分词 + GIN 索引，
 * 见 {@link com.dsmarket.modules.product.service.impl.ProductSearchServiceImpl}）。
 * 将来如需接入 Elasticsearch 等专用引擎，新增一个实现类即可，业务调用方无需改动。</p>
 *
 * <p>注意：本接口只负责"检索 + 排序 + 分页"，不承载商品 CRUD（CRUD 仍在 {@link ProductService}）。</p>
 */
public interface ProductSearchService {

    /**
     * 商城侧检索：只返回上架商品（status=1），含关键词全文检索、筛选、排序、分页。
     *
     * @param page  页码（从 1 开始）
     * @param size  每页大小
     * @param query 查询条件（keyword / categoryId / brand / minPrice / maxPrice / sortBy / sortOrder）
     * @return 商品分页结果
     */
    PageResult<ProductListVO> search(int page, int size, ProductQuery query);

    /**
     * 管理端检索：不过滤商品状态（可搜到已下架），检索逻辑同 {@link #search(int, int, ProductQuery)}。
     *
     * @param page  页码（从 1 开始）
     * @param size  每页大小
     * @param query 查询条件
     * @return 商品分页结果
     */
    PageResult<ProductListVO> searchAllStatus(int page, int size, ProductQuery query);

    /**
     * 商家侧检索：**只返回 {@code shopId} 这一家店铺的商品**，不过滤状态（商家要看得见自家已下架商品）。
     *
     * <p><b>为什么 {@code shopId} 是独立参数、而不是 {@link ProductQuery} 的一个字段</b>：
     * {@code ProductQuery} 是检索层入参对象，在 Controller 上由 {@code @ModelAttribute} 从
     * <b>请求参数</b>绑定 —— 把店铺ID放进去，等于把隔离开关交给客户端（改一个 query string
     * 就能读别家商品）。独立参数只能由服务端从 {@code SecurityContext} → {@code dsm_shop}
     * 解析后传入，客户端无从设置。这是 REQ-20260912 §4.4 硬规则 1。</p>
     *
     * <p><b>平台自营商品（{@code shop_id IS NULL}）不会出现在结果里</b> —— 判据是
     * {@code shop_id = :shopId} 的等值匹配，NULL 不参与等值比较。这正是 §10 第 2 条要的。</p>
     *
     * @param shopId 店铺ID。<b>必须非 null</b>：null 会退化成"全平台商品"，
     *               而那正是本方法要防的越权，故实现里直接拒绝而不做 null 兜底
     * @return 该店铺的商品分页结果
     */
    PageResult<ProductListVO> searchByShop(int page, int size, ProductQuery query, Long shopId);
}
