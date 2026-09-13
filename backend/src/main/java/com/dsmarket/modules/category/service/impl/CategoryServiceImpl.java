package com.dsmarket.modules.category.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.category.dto.CategoryNodeVO;
import com.dsmarket.modules.category.entity.Category;
import com.dsmarket.modules.category.mapper.CategoryMapper;
import com.dsmarket.modules.category.service.CategoryService;
import com.dsmarket.modules.product.dto.CategoryProductCount;
import com.dsmarket.modules.product.mapper.ProductMapper;
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
    /** 计数 SQL 属产品模块（§12.2 A1），故这里反向依赖 ProductMapper。mapper 无下游依赖，不成 bean 循环 */
    private final ProductMapper productMapper;

    @Override
    public List<CategoryNodeVO> getTree() {
        List<Category> all = categoryMapper.selectList(
                new LambdaQueryWrapper<Category>().eq(Category::getStatus, 1).orderByAsc(Category::getSortOrder));
        List<CategoryNodeVO> roots = buildTree(all);
        applyProductCounts(roots);
        return roots;
    }

    /**
     * 给树上每个节点填 {@code productCount} = 自身 + 整棵子树下公开可见商品数。
     *
     * <p><b>父子关系必须重建自「整表」，不能直接用 {@code roots}/{@code children} 这棵前台树</b>
     * （REQ-20260913 §4.9 / E11，全案最容易写错的一处）。原因是列表页的筛选走
     * {@link #getCategoryIdsIncludingDescendants}，而它内部是 {@code categoryMapper.selectList(null)}
     * —— <b>不过滤分类 status</b>。于是"某中间分类被禁用"时会出现两个后果：
     * ① 它自己不在前台树里，但其商品**仍会被 categoryId 筛中**；
     * ② {@link #buildTree} 会把它那些**启用的**子节点**提升成根节点**，
     * 整棵子树从父节点的 {@code children} 里消失。</p>
     *
     * <p>只按前台树累加，徽标就会小于点击后的实际条数（②那种情况甚至少一整棵子树），
     * 出现「胶囊标 0、点进去 3 条」。只有整表口径才能保证徽标与点击结果恒等。</p>
     */
    private void applyProductCounts(List<CategoryNodeVO> nodes) {
        Map<Long, Long> direct = new HashMap<>();
        for (CategoryProductCount row : productMapper.countPublicVisibleGroupByCategory()) {
            // category_id 为 NULL 的商品不归属任何分类，聚合行里键为 null —— 跳过，不影响任何节点的数
            if (row.getCategoryId() != null) {
                direct.merge(row.getCategoryId(), row.getCnt() == null ? 0L : row.getCnt(), Long::sum);
            }
        }

        // 整表父子关系。这里刻意**不加 status 过滤**，与 getCategoryIdsIncludingDescendants 的口径对齐。
        // 过滤掉 parentId 为 null 的行：groupingBy 不接受 null 键，而 null 父 = 根，本就不属于任何节点的子树
        Map<Long, List<Category>> wholeTable = categoryMapper.selectList(null).stream()
                .filter(c -> c.getParentId() != null)
                .collect(Collectors.groupingBy(Category::getParentId));

        Map<Long, Long> memo = new HashMap<>();
        for (CategoryNodeVO node : nodes) {
            fillCount(node, direct, wholeTable, memo);
        }
    }

    private void fillCount(CategoryNodeVO node, Map<Long, Long> direct,
                           Map<Long, List<Category>> wholeTable, Map<Long, Long> memo) {
        node.setProductCount(subtreeCount(node.getId(), direct, wholeTable, memo));
        for (CategoryNodeVO child : node.getChildren()) {
            fillCount(child, direct, wholeTable, memo);
        }
    }

    /** 递归求和，带记忆化。整表通常很小，且同一子树会被多个祖先问到 */
    private long subtreeCount(Long id, Map<Long, Long> direct,
                              Map<Long, List<Category>> wholeTable, Map<Long, Long> memo) {
        Long cached = memo.get(id);
        if (cached != null) {
            return cached;
        }
        long sum = direct.getOrDefault(id, 0L);
        for (Category child : wholeTable.getOrDefault(id, List.of())) {
            sum += subtreeCount(child.getId(), direct, wholeTable, memo);
        }
        memo.put(id, sum);
        return sum;
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
