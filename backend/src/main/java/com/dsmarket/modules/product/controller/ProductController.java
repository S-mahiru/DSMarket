package com.dsmarket.modules.product.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.modules.product.dto.ProductDetailVO;
import com.dsmarket.modules.product.dto.ProductListVO;
import com.dsmarket.modules.product.dto.ProductQuery;
import com.dsmarket.modules.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public ApiResponse<PageResult<ProductListVO>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder) {
        ProductQuery query = new ProductQuery();
        query.setKeyword(keyword);
        query.setCategoryId(categoryId);
        query.setBrand(brand);
        query.setMinPrice(minPrice);
        query.setMaxPrice(maxPrice);
        query.setSortBy(sortBy);
        query.setSortOrder(sortOrder);
        return ApiResponse.success(productService.page(page, size, query));
    }

    @GetMapping("/featured")
    public ApiResponse<List<ProductListVO>> featured(@RequestParam(defaultValue = "8") int limit) {
        return ApiResponse.success(productService.getFeatured(limit));
    }

    /**
     * 平台自营商品（{@code shop_id IS NULL}），供 {@code /shop/self} 自营专区使用
     * （REQ-20260913 §4.7 / §6.3）。
     *
     * <p><b>为什么放在 /products 而不是 /shops/self</b>：{@code /api/v1/shops/{id}} 的 {@code id}
     * 声明为 {@code Long}，路径段 {@code self} 转 Long 会失败 —— 按当前全局兜底，那是 <b>500 而不是 404</b>。
     * 放这里既避开该坑，语义上也确实就是"一个商品筛选"，且顺带复用
     * {@code /api/v1/products/**} 已有的 permitAll，不必再动 SecurityConfig。</p>
     *
     * <p><b>与 {@code /{id}} 不冲突</b>：Spring MVC 里字面量路径优先于路径变量模板，
     * {@code self-operated} 不会被当成 id（既有的 {@code /featured} 就是同一情形）。</p>
     */
    @GetMapping("/self-operated")
    public ApiResponse<PageResult<ProductListVO>> selfOperated(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder) {
        ProductQuery query = new ProductQuery();
        query.setSortBy(sortBy);
        query.setSortOrder(sortOrder);
        return ApiResponse.success(productService.selfOperatedPage(page, size, query));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.success(productService.getDetail(id));
    }
}
