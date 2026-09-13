package com.dsmarket.modules.shop.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.modules.product.dto.ProductListVO;
import com.dsmarket.modules.product.dto.ProductQuery;
import com.dsmarket.modules.product.service.ProductService;
import com.dsmarket.modules.shop.dto.ShopPublicVO;
import com.dsmarket.modules.shop.service.ShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前台公开店铺接口（REQ-20260913 §4.6 / §6.3），**无需登录**。
 *
 * <p><b>路径必须是复数 {@code /api/v1/shops}</b>：单数 {@code /api/v1/shop} 已被商家侧
 * {@link ShopController} 占用（{@code POST /apply}、{@code GET /mine}，靠
 * {@code anyRequest().authenticated()} 兜底）。若把公开前缀写成单数并入 permitAll，
 * 就等于把「提交入驻申请」变成匿名可调，且 {@code /shop/mine} 拿不到登录态直接炸。
 * 两者靠路径前缀区分，不复用同一个前缀。</p>
 *
 * <p>与 {@link ShopController} 的另一处区别：这里返回的 {@link ShopPublicVO} 只含 4 个字段，
 * 不是商家侧那个含 {@code userId}/{@code auditRemark} 的 {@code ShopVO}。</p>
 */
@RestController
@RequestMapping("/api/v1/shops")
@RequiredArgsConstructor
public class PublicShopController {

    private final ShopService shopService;
    private final ProductService productService;

    /**
     * 公开店铺信息。不存在 / 未开通 / 已软删 → **404**（三者不可区分，§8.3）。
     */
    @GetMapping("/{id}")
    public ApiResponse<ShopPublicVO> detail(@PathVariable Long id) {
        return ApiResponse.success(shopService.getPublicShop(id));
    }

    /**
     * 该店铺的在售商品。
     *
     * <p>先过 {@link ShopService#getPublicShop} 这道闸再查商品，顺序不能反：反了的话，
     * 对一家已关闭的店铺会返回<b>空列表</b> —— 前端渲染成"这家店暂时没有商品"，
     * 而事实是"这家店整店不可见"。两种语义在页面上长得一样，但契约完全不同（E1）。</p>
     */
    @GetMapping("/{id}/products")
    public ApiResponse<PageResult<ProductListVO>> products(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder) {
        shopService.getPublicShop(id);

        ProductQuery query = new ProductQuery();
        query.setSortBy(sortBy);
        query.setSortOrder(sortOrder);
        return ApiResponse.success(productService.publicShopPage(page, size, query, id));
    }
}
