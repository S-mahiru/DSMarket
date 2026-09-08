package com.dsmarket.modules.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.category.entity.Category;
import com.dsmarket.modules.category.mapper.CategoryMapper;
import com.dsmarket.modules.product.dto.ProductDetailVO;
import com.dsmarket.modules.product.dto.ProductFormDTO;
import com.dsmarket.modules.product.dto.ProductListVO;
import com.dsmarket.modules.product.dto.ProductQuery;
import com.dsmarket.modules.product.dto.ProductSkuFormDTO;
import com.dsmarket.modules.product.dto.ProductSkuVO;
import com.dsmarket.modules.product.dto.SpecDim;
import com.dsmarket.modules.product.dto.SpecItem;
import com.dsmarket.modules.product.entity.Product;
import com.dsmarket.modules.product.entity.ProductSku;
import com.dsmarket.modules.product.mapper.ProductMapper;
import com.dsmarket.modules.product.mapper.ProductSkuMapper;
import com.dsmarket.modules.product.service.ProductSearchService;
import com.dsmarket.modules.product.service.ProductService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductMapper productMapper;
    private final ProductSkuMapper productSkuMapper;
    private final CategoryMapper categoryMapper;
    private final ProductSearchService productSearchService;
    private final ObjectMapper objectMapper;

    @Override
    public PageResult<ProductListVO> page(int page, int size, ProductQuery query) {
        // 检索（关键词全文检索/筛选/排序/分页）统一委托给 ProductSearchService（当前 PG 实现）
        return productSearchService.search(page, size, query);
    }

    @Override
    public PageResult<ProductListVO> adminPage(int page, int size, ProductQuery query) {
        // 管理端检索同样走 ProductSearchService，但不过滤商品状态（可搜到已下架）
        return productSearchService.searchAllStatus(page, size, query);
    }

    @Override
    public List<ProductListVO> getFeatured(int limit) {
        List<Product> list = productMapper.selectList(
                new LambdaQueryWrapper<Product>()
                        .eq(Product::getIsFeatured, 1)
                        .eq(Product::getStatus, 1)
                        .orderByDesc(Product::getSales)
                        .last("LIMIT " + Math.min(limit, 20)));
        return list.stream().map(ProductListVO::from).toList();
    }

    @Override
    public ProductDetailVO getDetail(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null || product.getStatus() != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "商品不存在或已下架");
        }
        return buildDetail(product);
    }

    @Override
    public ProductDetailVO getAdminDetail(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "商品不存在");
        }
        return buildDetail(product);
    }

    private ProductDetailVO buildDetail(Product product) {
        ProductDetailVO vo = new ProductDetailVO();
        vo.setId(product.getId());
        vo.setName(product.getName());
        vo.setTitle(product.getTitle());
        vo.setBrief(product.getBrief());
        vo.setDescription(product.getDescription());
        vo.setMainImage(product.getMainImage());
        vo.setSubImages(parseImageList(product.getSubImages()));
        vo.setDetailImages(parseImageList(product.getDetailImages()));
        vo.setCategoryId(product.getCategoryId());
        vo.setCategoryName(resolveCategoryName(product.getCategoryId()));
        vo.setBrand(product.getBrand());
        vo.setUnit(product.getUnit());
        vo.setPrice(product.getPrice());
        vo.setOriginalPrice(product.getOriginalPrice());
        vo.setStock(product.getStock());
        vo.setSales(product.getSales());
        vo.setHasSku(product.getHasSku());
        vo.setIsFeatured(product.getIsFeatured());
        vo.setStatus(product.getStatus());

        // SKU 列表 + specDims
        List<ProductSku> skus = productSkuMapper.selectList(
                new LambdaQueryWrapper<ProductSku>()
                        .eq(ProductSku::getProductId, product.getId())
                        .orderByAsc(ProductSku::getSortOrder));
        Map<String, Set<String>> dimMap = new LinkedHashMap<>();
        for (ProductSku sku : skus) {
            List<SpecItem> specs = parseSpecs(sku.getSpecs());
            vo.getSkus().add(ProductSkuVO.from(sku, specs));
            for (SpecItem s : specs) {
                dimMap.computeIfAbsent(s.getKey(), k -> new LinkedHashSet<>()).add(s.getValue());
            }
        }
        for (Map.Entry<String, Set<String>> e : dimMap.entrySet()) {
            SpecDim dim = new SpecDim();
            dim.setKey(e.getKey());
            dim.setValues(new ArrayList<>(e.getValue()));
            vo.getSpecDims().add(dim);
        }
        return vo;
    }

    @Override
    @Transactional
    public Long create(ProductFormDTO form) {
        Product product = new Product();
        applyForm(product, form);
        productMapper.insert(product);
        saveSkus(product.getId(), form.getSkus());
        return product.getId();
    }

    @Override
    @Transactional
    public void update(Long id, ProductFormDTO form) {
        Product existing = productMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "商品不存在");
        }
        applyForm(existing, form);
        productMapper.updateById(existing);
        // SKU 批量替换：删除旧 SKU（逻辑删除），重插新 SKU
        productSkuMapper.delete(new LambdaQueryWrapper<ProductSku>().eq(ProductSku::getProductId, id));
        saveSkus(id, form.getSkus());
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "商品不存在");
        }
        product.setStatus(status);
        productMapper.updateById(product);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        productMapper.deleteById(id);
        productSkuMapper.delete(new LambdaQueryWrapper<ProductSku>().eq(ProductSku::getProductId, id));
    }

    private void applyForm(Product product, ProductFormDTO form) {
        product.setName(form.getName());
        product.setTitle(form.getTitle());
        product.setBrief(form.getBrief());
        product.setDescription(form.getDescription());
        product.setCategoryId(form.getCategoryId());
        product.setBrand(form.getBrand());
        product.setUnit(form.getUnit());
        product.setPrice(form.getPrice());
        product.setOriginalPrice(form.getOriginalPrice());
        product.setStock(form.getStock() == null ? 0 : form.getStock());
        product.setMainImage(form.getMainImage());
        product.setSubImages(writeJson(form.getSubImages()));
        product.setDetailImages(writeJson(form.getDetailImages()));
        product.setHasSku(form.getSkus() != null && !form.getSkus().isEmpty() ? 1 : 0);
        product.setIsFeatured(form.getIsFeatured() == null ? 0 : form.getIsFeatured());
        product.setWeight(form.getWeight());
        product.setStatus(form.getStatus() == null ? 1 : form.getStatus());
        product.setSortOrder(form.getSortOrder());
    }

    private void saveSkus(Long productId, List<ProductSkuFormDTO> skuForms) {
        if (skuForms == null) {
            return;
        }
        int order = 0;
        for (ProductSkuFormDTO f : skuForms) {
            ProductSku sku = new ProductSku();
            sku.setProductId(productId);
            sku.setSkuCode(f.getSkuCode());
            sku.setPrice(f.getPrice());
            sku.setOriginalPrice(f.getOriginalPrice());
            sku.setStock(f.getStock() == null ? 0 : f.getStock());
            sku.setSpecs(writeJson(f.getSpecs()));
            sku.setImage(f.getImage());
            sku.setWeight(f.getWeight());
            sku.setSortOrder(order++);
            sku.setStatus(f.getStatus() == null ? 1 : f.getStatus());
            productSkuMapper.insert(sku);
        }
    }

    private String writeJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveCategoryName(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        Category category = categoryMapper.selectById(categoryId);
        return category != null ? category.getName() : null;
    }

    private List<SpecItem> parseSpecs(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<SpecItem>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> parseImageList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
