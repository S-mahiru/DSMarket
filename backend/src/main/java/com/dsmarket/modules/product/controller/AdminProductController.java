package com.dsmarket.modules.product.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.modules.product.dto.ProductDetailVO;
import com.dsmarket.modules.product.dto.ProductFormDTO;
import com.dsmarket.modules.product.dto.ProductListVO;
import com.dsmarket.modules.product.dto.ProductQuery;
import com.dsmarket.modules.product.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/products")
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductService productService;

    @GetMapping
    public ApiResponse<PageResult<ProductListVO>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId) {
        ProductQuery query = new ProductQuery();
        query.setKeyword(keyword);
        query.setCategoryId(categoryId);
        return ApiResponse.success(productService.adminPage(page, size, query));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.success(productService.getAdminDetail(id));
    }

    @PostMapping
    public ApiResponse<Long> create(@Valid @RequestBody ProductFormDTO form) {
        return ApiResponse.success(productService.create(form));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody ProductFormDTO form) {
        productService.update(id, form);
        return ApiResponse.success();
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        productService.updateStatus(id, status);
        return ApiResponse.success();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ApiResponse.success();
    }
}
