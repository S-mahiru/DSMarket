package com.dsmarket.modules.product.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.common.util.SecurityUtils;
import com.dsmarket.modules.product.dto.ProductDetailVO;
import com.dsmarket.modules.product.dto.ProductFormDTO;
import com.dsmarket.modules.product.dto.ProductListVO;
import com.dsmarket.modules.product.dto.ProductQuery;
import com.dsmarket.modules.product.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家商品管理接口（REQ-20260912 §4.5）。
 *
 * <p><b>两道闸，缺一不可</b>：① {@code SecurityConfig} 里 {@code /api/v1/merchant/**} 锁
 * {@code hasRole("MERCHANT")}（精确匹配，ADMIN 也进不来 —— D3）；② 服务层的
 * {@code requireActiveShopId} + {@code requireOwnedProduct}（隔离真正的落点）。</p>
 *
 * <p><b>本控制器不接收任何店铺ID参数</b>：店铺一律由服务端从 {@code SecurityContext} 解析。
 * 列表条件也是逐个 {@code @RequestParam} 手工装配成 {@link ProductQuery}，而**不是**
 * {@code @ModelAttribute} 直接绑定 —— 后者会让"哪些字段能被客户端设置"变成隐式的
 * （§4.4 硬规则 1）。</p>
 *
 * <p><b>没有 DELETE 端点</b>：D1 已拍板本期不开放商家删除权，只提供上下架。
 * 删除是逻辑删除而订单按 {@code product_id} 关联商品，风险高于收益；"下架"已能表达"不卖了"。</p>
 */
@RestController
@RequestMapping("/api/v1/merchant/products")
@RequiredArgsConstructor
public class MerchantProductController {

    private final ProductService productService;

    /** 自家商品列表（含已下架，不含平台自营） */
    @GetMapping
    public ApiResponse<PageResult<ProductListVO>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId) {
        ProductQuery query = new ProductQuery();
        query.setKeyword(keyword);
        query.setCategoryId(categoryId);
        return ApiResponse.success(productService.merchantPage(SecurityUtils.requireUserId(), page, size, query));
    }

    /** 自家商品详情；非自家商品 → 404 */
    @GetMapping("/{id}")
    public ApiResponse<ProductDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.success(productService.getMerchantDetail(SecurityUtils.requireUserId(), id));
    }

    /** 新建商品，归属强制为自家店铺 */
    @PostMapping
    public ApiResponse<Long> create(@Valid @RequestBody ProductFormDTO form) {
        return ApiResponse.success(productService.createForShop(SecurityUtils.requireUserId(), form));
    }

    /** 编辑自家商品；非自家商品 → 404 */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody ProductFormDTO form) {
        productService.updateForShop(SecurityUtils.requireUserId(), id, form);
        return ApiResponse.success();
    }

    /** 上下架自家商品；非自家商品 → 404 */
    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        productService.updateStatusForShop(SecurityUtils.requireUserId(), id, status);
        return ApiResponse.success();
    }
}
