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
}
