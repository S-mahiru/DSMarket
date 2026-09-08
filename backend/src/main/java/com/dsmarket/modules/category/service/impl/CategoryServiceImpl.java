package com.dsmarket.modules.category.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.category.dto.CategoryNodeVO;
import com.dsmarket.modules.category.entity.Category;
import com.dsmarket.modules.category.mapper.CategoryMapper;
import com.dsmarket.modules.category.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryMapper categoryMapper;

    @Override
    public List<CategoryNodeVO> getTree() {
        List<Category> all = categoryMapper.selectList(
                new LambdaQueryWrapper<Category>().eq(Category::getStatus, 1).orderByAsc(Category::getSortOrder));
        return buildTree(all);
    }

    @Override
    public List<Long> getCategoryIdsIncludingDescendants(Long categoryId) {
        if (categoryId == null) {
            return List.of();
        }
        List<Category> all = categoryMapper.selectList(null);
        Map<Long, List<Category>> childrenMap = all.stream()
                .collect(Collectors.groupingBy(Category::getParentId));
        List<Long> result = new ArrayList<>();
        collectDescendants(categoryId, childrenMap, result);
        return result;
    }

    @Override
    public List<Category> getAdminList() {
        return categoryMapper.selectList(
                new LambdaQueryWrapper<Category>().orderByAsc(Category::getSortOrder));
    }

    @Override
    @Transactional
    public Category create(Category category) {
        if (!StringUtils.hasText(category.getName())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "分类名称不能为空");
        }
        category.setId(null);
        category.setLevel(resolveLevel(category.getParentId()));
        category.setStatus(category.getStatus() == null ? 1 : category.getStatus());
        categoryMapper.insert(category);
        return category;
    }

    @Override
    @Transactional
    public Category update(Category category) {
        Category existing = categoryMapper.selectById(category.getId());
        if (existing == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "分类不存在");
        }
        if (category.getParentId() != null) {
            existing.setParentId(category.getParentId());
            existing.setLevel(resolveLevel(category.getParentId()));
        }
        if (StringUtils.hasText(category.getName())) {
            existing.setName(category.getName());
        }
        if (category.getSortOrder() != null) {
            existing.setSortOrder(category.getSortOrder());
        }
        if (category.getIcon() != null) {
            existing.setIcon(category.getIcon());
        }
        if (category.getImage() != null) {
            existing.setImage(category.getImage());
        }
        if (category.getStatus() != null) {
            existing.setStatus(category.getStatus());
        }
        categoryMapper.updateById(existing);
        return existing;
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Long children = categoryMapper.selectCount(
                new LambdaQueryWrapper<Category>().eq(Category::getParentId, id));
        if (children > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "存在子分类，无法删除");
        }
        categoryMapper.deleteById(id);
    }

    private int resolveLevel(Long parentId) {
        if (parentId == null || parentId == 0) {
            return 1;
        }
        Category parent = categoryMapper.selectById(parentId);
        return parent != null ? parent.getLevel() + 1 : 1;
    }

    private void collectDescendants(Long parentId, Map<Long, List<Category>> childrenMap, List<Long> result) {
        result.add(parentId);
        List<Category> children = childrenMap.getOrDefault(parentId, List.of());
        for (Category child : children) {
            collectDescendants(child.getId(), childrenMap, result);
        }
    }

    private List<CategoryNodeVO> buildTree(List<Category> categories) {
        Map<Long, CategoryNodeVO> nodeMap = new HashMap<>();
        for (Category c : categories) {
            CategoryNodeVO node = new CategoryNodeVO();
            node.setId(c.getId());
            node.setName(c.getName());
            node.setLevel(c.getLevel());
            nodeMap.put(c.getId(), node);
        }
        List<CategoryNodeVO> roots = new ArrayList<>();
        for (Category c : categories) {
            CategoryNodeVO node = nodeMap.get(c.getId());
            Long parentId = c.getParentId();
            if (parentId != null && parentId != 0 && nodeMap.containsKey(parentId)) {
                nodeMap.get(parentId).getChildren().add(node);
            } else {
                roots.add(node);
            }
        }
        return roots;
    }
}
