package com.dsmarket.modules.category.service;

import com.dsmarket.modules.category.dto.CategoryNodeVO;
import com.dsmarket.modules.category.entity.Category;

import java.util.List;

public interface CategoryService {

    /** 前台分类树（仅启用分类） */
    List<CategoryNodeVO> getTree();

    /** 获取某分类及其所有后代分类 ID（含自身） */
    List<Long> getCategoryIdsIncludingDescendants(Long categoryId);

    /** 管理端全量平铺列表（含禁用） */
    List<Category> getAdminList();

    Category create(Category category);

    Category update(Category category);

    void delete(Long id);
}
