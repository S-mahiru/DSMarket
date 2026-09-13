package com.dsmarket.modules.product.service;

import com.dsmarket.common.domain.PageResult;
import com.dsmarket.modules.product.dto.ProductDetailVO;
import com.dsmarket.modules.product.dto.ProductFormDTO;
import com.dsmarket.modules.product.dto.ProductListVO;
import com.dsmarket.modules.product.dto.ProductQuery;

import java.util.List;

public interface ProductService {

    PageResult<ProductListVO> page(int page, int size, ProductQuery query);

    PageResult<ProductListVO> adminPage(int page, int size, ProductQuery query);

    // ==================== 商家侧（REQ-20260912 §4.5）====================
    // 与上面的 admin 方法是**两套**，不复用：admin 方法没有归属校验，商家端点复用即等于无隔离
    // （§4.4 硬规则 2）。每个方法的第一步都是 requireActiveShopId → requireOwnedProduct。

    /** 商家侧列表：只返回自家店铺商品（不含平台自营），不过滤 status（可看已下架） */
    PageResult<ProductListVO> merchantPage(Long userId, int page, int size, ProductQuery query);

    /** 商家侧详情：过归属校验，越权 404 */
    ProductDetailVO getMerchantDetail(Long userId, Long id);

    /** 商家侧新建：`shop_id` 由服务端强制置为自家店铺 */
    Long createForShop(Long userId, ProductFormDTO form);

    /** 商家侧编辑：过归属校验；归属不可被请求改写 */
    void updateForShop(Long userId, Long id, ProductFormDTO form);

    /** 商家侧上下架：过归属校验。**本期不提供删除**（D1：删除是逻辑删除且订单按 product_id 关联） */
    void updateStatusForShop(Long userId, Long id, Integer status);

    // ==================== 前台公开（REQ-20260913 §4.6 / §4.7）====================

    /**
     * 前台店铺页：某公开店铺的在售商品。
     *
     * <p><b>本方法不做店铺可见性校验</b> —— 调用方须先走 {@code ShopService.getPublicShop}，
     * 让"店铺不存在/已关闭"落 404，而不是返回一个空列表冒充"这家店没货"。</p>
     */
    PageResult<ProductListVO> publicShopPage(int page, int size, ProductQuery query, Long shopId);

    /** 前台自营专区：{@code shop_id IS NULL} 的公开可见商品 */
    PageResult<ProductListVO> selfOperatedPage(int page, int size, ProductQuery query);

    List<ProductListVO> getFeatured(int limit);

    ProductDetailVO getDetail(Long id);

    ProductDetailVO getAdminDetail(Long id);

    Long create(ProductFormDTO form);

    void update(Long id, ProductFormDTO form);

    void updateStatus(Long id, Integer status);

    void delete(Long id);
}
