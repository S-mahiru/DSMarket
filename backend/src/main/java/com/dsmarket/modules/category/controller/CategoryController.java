package com.dsmarket.modules.category.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.modules.category.dto.CategoryNodeVO;
import com.dsmarket.modules.category.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    public ApiResponse<List<CategoryNodeVO>> getTree() {
        return ApiResponse.success(categoryService.getTree());
    }
}
