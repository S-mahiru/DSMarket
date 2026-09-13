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
import com.dsmarket.modules.product.support.ProductVisibility;
import com.dsmarket.modules.shop.entity.Shop;
import com.dsmarket.modules.shop.mapper.ShopMapper;
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
    private final ShopMapper shopMapper;
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

    // ==================== 商家侧（REQ-20260912 §4.4 / §4.5）====================

    @Override
    public PageResult<ProductListVO> merchantPage(Long userId, int page, int size, ProductQuery query) {
        Long shopId = requireActiveShopId(userId);
        return productSearchService.searchByShop(page, size, query, shopId);
    }

    @Override
    public ProductDetailVO getMerchantDetail(Long userId, Long id) {
        Long shopId = requireActiveShopId(userId);
        return buildDetail(requireOwnedProduct(id, shopId));
    }

    @Override
    @Transactional
    public Long createForShop(Long userId, ProductFormDTO form) {
        Long shopId = requireActiveShopId(userId);
        Product product = new Product();
        applyForm(product, form);
        // 归属**只在这里赋值**，来源是上面解析出的店铺。ProductFormDTO 里根本没有 shopId 字段，
        // 客户端多传的 shopId 连绑定都不会发生（§10 第 5 条）。
        product.setShopId(shopId);
        productMapper.insert(product);
        saveSkus(product.getId(), form.getSkus());
        return product.getId();
    }

    @Override
    @Transactional
    public void updateForShop(Long userId, Long id, ProductFormDTO form) {
        Long shopId = requireActiveShopId(userId);
        Product existing = requireOwnedProduct(id, shopId);
        applyForm(existing, form);
        // 显式重设：applyForm 本来就不碰 shopId（现有值会原样保留），这一行是把
        // "归属不可被表单改写"这条规则钉在代码里，而不是靠"读 applyForm 发现它没写"来保证。
        existing.setShopId(shopId);
        productMapper.updateById(existing);
        // SKU 批量替换：与 admin 侧同构（删旧插新）。此处已过归属校验，id 一定是自家的
        productSkuMapper.delete(new LambdaQueryWrapper<ProductSku>().eq(ProductSku::getProductId, id));
        saveSkus(id, form.getSkus());
    }

    @Override
    public void updateStatusForShop(Long userId, Long id, Integer status) {
        Long shopId = requireActiveShopId(userId);
        Product product = requireOwnedProduct(id, shopId);
        product.setStatus(status);
        productMapper.updateById(product);
    }

    /**
     * 当前用户的**已开通**店铺ID。所有商家侧操作的入口闸。
     *
     * <p>无店铺（未入驻）或店铺未开通 → **403**（§4.4 异常行）。403 而非 404 是刻意的：
     * 这里拒绝的是"你这个账号没有商家身份"，与"某个商品存不存在"无关，不涉及资源泄露。
     * 注意与下面 {@link #requireOwnedProduct} 的 404 区分开。</p>
     */
    private Long requireActiveShopId(Long userId) {
        Shop shop = shopMapper.selectOne(new LambdaQueryWrapper<Shop>()
                .eq(Shop::getUserId, userId)
                .last("LIMIT 1"));
        if (shop == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "尚未入驻，无商家权限");
        }
        if (!Integer.valueOf(1).equals(shop.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "店铺未开通，无商家权限");
        }
        return shop.getId();
    }

    /**
     * 归属校验：**所有**商家侧 by-id 操作的第一道闸，必须先过它再做任何事。
     *
     * <p>两种失败都返回 **404，而不是 403**（§8）：403 等于告诉对方"这个 id 是存在的，
     * 只是不归你"，可以拿来枚举全平台商品；404 让"不存在"和"不归你"无法区分。</p>
     *
     * <p>顺带覆盖了两种情况：商品已被逻辑删除（{@code selectById} 带 {@code @TableLogic}
     * → 返回 null），以及平台自营商品（{@code shop_id IS NULL}）—— 后者与任何 shopId
     * 都不相等，商家碰不到自营商品（§10 第 3 条）。</p>
     */
    private Product requireOwnedProduct(Long productId, Long shopId) {
        Product product = productMapper.selectById(productId);
        if (product == null || !shopId.equals(product.getShopId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "商品不存在");
        }
        return product;
    }

    // ==================== 前台公开（REQ-20260913 §4.6 / §4.7）====================

    @Override
    public PageResult<ProductListVO> publicShopPage(int page, int size, ProductQuery query, Long shopId) {
        return productSearchService.searchPublicByShop(page, size, query, shopId);
    }

    @Override
    public PageResult<ProductListVO> selfOperatedPage(int page, int size, ProductQuery query) {
        return productSearchService.searchSelfOperated(page, size, query);
    }

    @Override
    public List<ProductListVO> getFeatured(int limit) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<Product>()
                .eq(Product::getIsFeatured, 1)
                .eq(Product::getStatus, 1)
                .orderByDesc(Product::getSales);
        // 首页推荐同属商城侧，口径须与列表页逐字一致（§4.8 落点 2）。
        // 漏掉这里，关闭店铺的商品会在首页"继续营业"，而列表里已经查不到 —— 两处口径分叉。
        ProductVisibility.apply(wrapper);
        wrapper.last("LIMIT " + Math.min(limit, 20));
        return productMapper.selectList(wrapper).stream().map(ProductListVO::from).toList();
    }

    @Override
    public ProductDetailVO getDetail(Long id) {
        // 公开详情的可见性口径必须与列表、首页推荐**逐字一致**（§4.8 落点 3）。
        // 判据自带 status = 1，原先那句 product.getStatus() != 1 的检查并入其中。
        // 于是"直接按 URL 访问已关闭店铺的商品"同样落 404，不会出现"列表看不到、详情打得开"。
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Product::getId, id);
        ProductVisibility.apply(wrapper);
        Product product = productMapper.selectOne(wrapper);
        if (product == null) {
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
        // 商家行（REQ-20260913 §4.5）。shopId 为 null 即平台自营，前端走 /shop/self。
        // 公开详情走到这里时，可见性判据已保证所属店铺 status=1 且未软删，故 shopName 必能解析出来；
        // 管理端/商家端看到已关闭店铺的详情时 shopName 可能为 null（selectById 被 @TableLogic 滤掉），
        // 这是可接受的——shopId 仍在，足够定位。
        vo.setShopId(product.getShopId());
        vo.setShopName(resolveShopName(product.getShopId()));

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

    /**
     * 店铺名（REQ-20260913 §4.5）。{@code shopId == null} = 平台自营，返回 null 让前端渲染「平台自营」。
     *
     * <p>只取名字，**不返回整个 Shop 实体** —— 公开详情是匿名可访问的，返回实体即泄漏
     * {@code userId}/{@code status}/{@code auditRemark}。这也正是公开店铺另立 {@code ShopPublicVO}
     * 的同一条理由。</p>
     */
    private String resolveShopName(Long shopId) {
        if (shopId == null) {
            return null;
        }
        Shop shop = shopMapper.selectById(shopId);
        return shop != null ? shop.getShopName() : null;
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
