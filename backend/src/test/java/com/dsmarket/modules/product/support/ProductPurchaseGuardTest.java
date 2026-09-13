package com.dsmarket.modules.product.support;

import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.modules.product.entity.Product;
import com.dsmarket.modules.product.mapper.ProductMapper;
import com.dsmarket.modules.shop.entity.Shop;
import com.dsmarket.modules.shop.mapper.ShopMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link ProductPurchaseGuard} 的分支与文案单元测试。
 *
 * <p><b>本类故意不测 SQL</b>：这里 {@code productMapper} 是 mock，
 * 「公开可见」谓词根本没有被执行 —— 换句话说，把 {@code ProductVisibility.SQL} 整个写错，
 * 本类照样全绿。判据本身由 {@link ProductVisibilityIntegrationTest}（真库）覆盖，
 * 本类与 {@link ProductPurchaseGuardIntegrationTest}（真库，写路径端到端）合起来才算完整。
 * 这正是「突变必须打在探针够得着的那一层」。</p>
 */
@ExtendWith(MockitoExtension.class)
class ProductPurchaseGuardTest {

    @Mock
    private ProductMapper productMapper;
    @Mock
    private ShopMapper shopMapper;

    @InjectMocks
    private ProductPurchaseGuard guard;

    // ---------- 放行 ----------

    @Test
    void selfOperatedProduct_passesWithoutTouchingShop() {
        Product p = product(10L, "自营商品", 1, null);
        when(productMapper.selectOne(any())).thenReturn(p);

        assertSame(p, guard.requirePurchasable(10L));
        // 自营分支必须**短路**：一旦这里去查 dsm_shop，说明判据被写成了「先查店铺再看」
        verifyNoInteractions(shopMapper);
    }

    @Test
    void openShopProduct_passes() {
        Product p = product(11L, "商家商品", 1, 7L);
        when(productMapper.selectOne(any())).thenReturn(p);

        assertSame(p, guard.requirePurchasable(11L));
    }

    // ---------- 拦截：文案必须说清是「店铺」的问题 ----------

    @Test
    void closedShop_throwsAndBlamesTheShop() {
        when(productMapper.selectOne(any())).thenReturn(null);
        when(productMapper.selectById(11L)).thenReturn(product(11L, "商家商品", 1, 7L));
        when(shopMapper.selectById(7L)).thenReturn(shop(7L, 3));

        BusinessException ex = assertThrows(BusinessException.class, () -> guard.requirePurchasable(11L));
        assertEquals("商品所属店铺已关闭: 商家商品", ex.getMessage());
    }

    @Test
    void pendingOrRejectedShop_usesTheSameWording() {
        // 文案不区分 0/2/3：对买家而言「这店现在不卖东西」是同一件事，
        // 区分待审核 / 已驳回 / 已关闭只会把平台内部状态暴露出去（与 §8.3 同源）。
        when(productMapper.selectOne(any())).thenReturn(null);
        when(productMapper.selectById(11L)).thenReturn(product(11L, "商家商品", 1, 7L));
        when(shopMapper.selectById(7L)).thenReturn(shop(7L, 0));

        BusinessException ex = assertThrows(BusinessException.class, () -> guard.requirePurchasable(11L));
        assertEquals("商品所属店铺已关闭: 商家商品", ex.getMessage());
    }

    @Test
    void softDeletedShop_blamesTheShopToo() {
        // @TableLogic 让 selectById 对软删店铺返回 null —— 不能因此掉进「商品已下架」文案
        when(productMapper.selectOne(any())).thenReturn(null);
        when(productMapper.selectById(11L)).thenReturn(product(11L, "商家商品", 1, 7L));
        when(shopMapper.selectById(7L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> guard.requirePurchasable(11L));
        assertEquals("商品所属店铺已关闭: 商家商品", ex.getMessage());
    }

    // ---------- 拦截：通用文案 ----------

    @Test
    void offShelfProduct_throwsGeneric() {
        when(productMapper.selectOne(any())).thenReturn(null);
        when(productMapper.selectById(10L)).thenReturn(product(10L, "下架商品", 0, null));

        BusinessException ex = assertThrows(BusinessException.class, () -> guard.requirePurchasable(10L));
        assertEquals("商品已下架或不存在", ex.getMessage());
    }

    @Test
    void missingProduct_throwsGeneric() {
        when(productMapper.selectOne(any())).thenReturn(null);
        when(productMapper.selectById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> guard.requirePurchasable(999L));
        assertEquals("商品已下架或不存在", ex.getMessage());
    }

    // ---------- 边界 ----------

    @Test
    void nullProductId_throwsGenericWithoutQuerying() {
        BusinessException ex = assertThrows(BusinessException.class, () -> guard.requirePurchasable(null));
        assertEquals("商品已下架或不存在", ex.getMessage());
        // id 为空时不应产生任何查询（尤其是不能拼出 `id = null` 这种永假条件去查库）
        verifyNoInteractions(productMapper, shopMapper);
    }

    // ---------- 辅助 ----------

    private Product product(Long id, String name, Integer status, Long shopId) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setStatus(status);
        p.setShopId(shopId);
        p.setPrice(new BigDecimal("66.00"));
        p.setStock(10);
        return p;
    }

    private Shop shop(Long id, Integer status) {
        Shop s = new Shop();
        s.setId(id);
        s.setShopName("测试店铺");
        s.setStatus(status);
        return s;
    }
}
