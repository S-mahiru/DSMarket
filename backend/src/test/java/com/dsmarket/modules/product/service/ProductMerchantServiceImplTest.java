package com.dsmarket.modules.product.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.modules.product.dto.ProductFormDTO;
import com.dsmarket.modules.product.dto.ProductListVO;
import com.dsmarket.modules.product.dto.ProductQuery;
import com.dsmarket.modules.product.entity.Product;
import com.dsmarket.modules.product.mapper.ProductMapper;
import com.dsmarket.modules.product.mapper.ProductSkuMapper;
import com.dsmarket.modules.product.service.impl.ProductServiceImpl;
import com.dsmarket.modules.shop.entity.Shop;
import com.dsmarket.modules.shop.mapper.ShopMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 商家侧商品服务单测（REQ-20260912 §4.4）：两道闸——{@code requireActiveShopId}（403）
 * 与 {@code requireOwnedProduct}（404）——以及"归属由服务端赋值、不可被请求改写"。
 *
 * <p>这里只覆盖**分支**；SQL 是否真带上 {@code shop_id} 由
 * {@link MerchantProductIsolationIntegrationTest} 用真库验证，两层缺一不可。</p>
 */
@ExtendWith(MockitoExtension.class)
class ProductMerchantServiceImplTest {

    @Mock
    private ProductMapper productMapper;
    @Mock
    private ProductSkuMapper productSkuMapper;
    @Mock
    private com.dsmarket.modules.category.mapper.CategoryMapper categoryMapper;
    @Mock
    private ProductSearchService productSearchService;
    @Mock
    private ShopMapper shopMapper;
    /** 用真 ObjectMapper（ProductServiceImpl 用它序列化图片 JSON），mock 掉会掩盖空指针 */
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ProductServiceImpl service;

    private static final Long USER_A = 100L;
    private static final Long SHOP_A = 10L;
    private static final Long SHOP_B = 20L;

    private Shop shop(Long id, Long userId, Integer status) {
        Shop s = new Shop();
        s.setId(id);
        s.setUserId(userId);
        s.setShopName("S" + id);
        s.setStatus(status);
        return s;
    }

    private Product product(Long id, Long shopId) {
        Product p = new Product();
        p.setId(id);
        p.setName("P" + id);
        p.setShopId(shopId);
        p.setStatus(1);
        return p;
    }

    private ProductFormDTO form(String name) {
        ProductFormDTO f = new ProductFormDTO();
        f.setName(name);
        f.setCategoryId(1L);
        return f;
    }

    // ---------- 闸①：无商家身份 / 店铺未开通 → 403 ----------

    @Test
    void merchantPage_noShop_throws403() {
        when(shopMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.merchantPage(USER_A, 1, 10, new ProductQuery()));
        assertEquals(403, ex.getCode());
        // 关键：无商家身份时**不得**触碰检索层——否则等于放行了一次查询
        verifyNoInteractions(productSearchService);
    }

    @Test
    void merchantPage_shopNotActive_throws403() {
        // status=0 待审核：已提交入驻但没过审，仍不具商家权限
        when(shopMapper.selectOne(any(Wrapper.class))).thenReturn(shop(SHOP_A, USER_A, 0));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.merchantPage(USER_A, 1, 10, new ProductQuery()));
        assertEquals(403, ex.getCode());
        verifyNoInteractions(productSearchService);
    }

    @Test
    void merchantPage_activeShop_delegatesWithOwnShopId() {
        when(shopMapper.selectOne(any(Wrapper.class))).thenReturn(shop(SHOP_A, USER_A, 1));
        when(productSearchService.searchByShop(anyInt(), anyInt(), any(ProductQuery.class), any()))
                .thenReturn(new PageResult<>());

        service.merchantPage(USER_A, 1, 10, new ProductQuery());

        // 传下去的必须是**服务端解析出的** shopId，而不是任何请求侧的值
        verify(productSearchService).searchByShop(1, 10, new ProductQuery(), SHOP_A);
    }

    // ---------- 闸②：跨店铺 / 不存在 / 自营 → 404 ----------

    @Test
    void getMerchantDetail_otherShopProduct_throws404() {
        when(shopMapper.selectOne(any(Wrapper.class))).thenReturn(shop(SHOP_A, USER_A, 1));
        when(productMapper.selectById(8L)).thenReturn(product(8L, SHOP_B));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getMerchantDetail(USER_A, 8L));
        // 404 而非 403：403 等于承认"这个 id 存在，只是不归你"，可用来枚举全平台商品（§8）
        assertEquals(404, ex.getCode());
    }

    @Test
    void getMerchantDetail_platformOwnedProduct_throws404() {
        // shop_id IS NULL = 平台自营。与任何 shopId 都不相等，商家碰不到（§10 第 3 条）
        when(shopMapper.selectOne(any(Wrapper.class))).thenReturn(shop(SHOP_A, USER_A, 1));
        when(productMapper.selectById(1L)).thenReturn(product(1L, null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getMerchantDetail(USER_A, 1L));
        assertEquals(404, ex.getCode());
    }

    @Test
    void getMerchantDetail_deletedProduct_throws404() {
        // selectById 带 @TableLogic → 逻辑删除的行返回 null，与"不存在"同路
        when(shopMapper.selectOne(any(Wrapper.class))).thenReturn(shop(SHOP_A, USER_A, 1));
        when(productMapper.selectById(7L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getMerchantDetail(USER_A, 7L));
        assertEquals(404, ex.getCode());
    }

    @Test
    void updateForShop_otherShopProduct_throws404AndWritesNothing() {
        when(shopMapper.selectOne(any(Wrapper.class))).thenReturn(shop(SHOP_A, USER_A, 1));
        when(productMapper.selectById(8L)).thenReturn(product(8L, SHOP_B));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateForShop(USER_A, 8L, form("Hijacked")));
        assertEquals(404, ex.getCode());
        // "只断言状态码"不够：必须证明**没有任何落库动作**（纪律见 REQ §12）
        verify(productMapper, never()).updateById(any(Product.class));
        verify(productSkuMapper, never()).delete(any(Wrapper.class));
        verify(productSkuMapper, never()).insert(any(com.dsmarket.modules.product.entity.ProductSku.class));
    }

    @Test
    void updateStatusForShop_otherShopProduct_throws404AndWritesNothing() {
        when(shopMapper.selectOne(any(Wrapper.class))).thenReturn(shop(SHOP_A, USER_A, 1));
        when(productMapper.selectById(8L)).thenReturn(product(8L, SHOP_B));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateStatusForShop(USER_A, 8L, 0));
        assertEquals(404, ex.getCode());
        verify(productMapper, never()).updateById(any(Product.class));
    }

    // ---------- 归属由服务端赋值 ----------

    @Test
    void createForShop_pinsShopIdToResolvedShop() {
        when(shopMapper.selectOne(any(Wrapper.class))).thenReturn(shop(SHOP_A, USER_A, 1));
        when(productMapper.insert(any(Product.class))).thenAnswer(inv -> {
            inv.getArgument(0, Product.class).setId(555L);
            return 1;
        });

        Long id = service.createForShop(USER_A, form("New Product"));

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productMapper).insert(captor.capture());
        assertEquals(SHOP_A, captor.getValue().getShopId());
        assertEquals(555L, id);
    }

    @Test
    void updateForShop_ownProduct_rePinsShopId() {
        // 回归防线：applyForm 目前不碰 shopId，但"归属不可被表单改写"这条规则
        // 不能靠"读 applyForm 发现它没写"来保证——显式重设，谁改坏了这里立刻红。
        when(shopMapper.selectOne(any(Wrapper.class))).thenReturn(shop(SHOP_A, USER_A, 1));
        when(productMapper.selectById(7L)).thenReturn(product(7L, SHOP_A));

        service.updateForShop(USER_A, 7L, form("Edited"));

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productMapper).updateById(captor.capture());
        assertEquals(SHOP_A, captor.getValue().getShopId());
        assertEquals("Edited", captor.getValue().getName());
    }

    /**
     * §4.4 硬规则 1 的**结构保证**：{@code shopId} 根本不在表单 DTO 里，客户端就无从设置。
     * 这是一条防回归断言——谁哪天给 {@code ProductFormDTO} 加了 shopId 字段，这里立刻红。
     *
     * <p>HTTP 层的实证（真发一个 body 里带 {@code "shopId": 别家id} 的请求）已在真机做过，
     * 见 REQ §10 第 5 条；本仓库没有 MockMvc 测试基建，故此处只做结构断言 + 上面的赋值断言。</p>
     */
    @Test
    void productFormDTO_hasNoShopIdField() {
        assertTrue(Arrays.stream(ProductFormDTO.class.getDeclaredFields())
                        .noneMatch(f -> f.getName().equalsIgnoreCase("shopId")),
                "ProductFormDTO 不得出现 shopId 字段：归属一旦能从请求绑定，隔离即失守");
    }

    // ---------- 传递 ----------

    @Test
    void merchantPage_returnsPageFromSearchLayer() {
        when(shopMapper.selectOne(any(Wrapper.class))).thenReturn(shop(SHOP_A, USER_A, 1));
        PageResult<ProductListVO> expected = new PageResult<>();
        when(productSearchService.searchByShop(eq(2), eq(5), any(ProductQuery.class), eq(SHOP_A)))
                .thenReturn(expected);

        assertSame(expected, service.merchantPage(USER_A, 2, 5, new ProductQuery()));
    }
}
