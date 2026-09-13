package com.dsmarket.modules.cart.service.impl;

import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.cart.entity.Cart;
import com.dsmarket.modules.cart.mapper.CartMapper;
import com.dsmarket.modules.product.entity.Product;
import com.dsmarket.modules.product.mapper.ProductMapper;
import com.dsmarket.modules.product.mapper.ProductSkuMapper;
import com.dsmarket.modules.product.support.ProductPurchaseGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 购物车服务单元测试，聚焦 Q6 新增的「加购前过可购买判据」。
 *
 * <p>这里 {@code ProductPurchaseGuard} 是 mock —— 本类只证明**接线正确**
 * （守门人被调用、被拦下时一行都不写）；「什么算不可购买」由
 * {@code ProductPurchaseGuardTest} 与 {@code ProductPurchaseGuardIntegrationTest} 覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class CartServiceImplTest {

    @Mock
    private CartMapper cartMapper;
    @Mock
    private ProductMapper productMapper;
    @Mock
    private ProductSkuMapper productSkuMapper;
    @Mock
    private ProductPurchaseGuard productPurchaseGuard;

    @InjectMocks
    private CartServiceImpl service;

    @Test
    void add_purchasable_insertsNewRow() {
        when(productPurchaseGuard.requirePurchasable(10L)).thenReturn(product(10L));
        when(cartMapper.selectOne(any())).thenReturn(null);

        service.add(1L, 10L, null, 2);

        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartMapper).insert((Cart) captor.capture());
        assertEquals(1L, captor.getValue().getUserId());
        assertEquals(10L, captor.getValue().getProductId());
        assertEquals(2, captor.getValue().getQuantity());
        assertEquals(1, captor.getValue().getChecked());
    }

    @Test
    void add_existingRow_stillMergesQuantity() {
        // 回归：守门人不得改变「同商品累加数量」这条既有行为
        Cart existing = new Cart();
        existing.setId(5L);
        existing.setUserId(1L);
        existing.setProductId(10L);
        existing.setQuantity(3);
        when(productPurchaseGuard.requirePurchasable(10L)).thenReturn(product(10L));
        when(cartMapper.selectOne(any())).thenReturn(existing);

        service.add(1L, 10L, null, 2);

        assertEquals(5, existing.getQuantity());
        verify(cartMapper).updateById(existing);
        verify(cartMapper, never()).insert(any(Cart.class));
    }

    @Test
    void add_unpurchasable_throwsAndWritesNothing() {
        // 真机实测的原始缺陷：关店后 POST /cart 返回 200 并落库一行。
        // 这条断言的就是「一行都不写」—— 只断言抛异常会漏掉「抛之前已经写了一半」。
        when(productPurchaseGuard.requirePurchasable(10L))
                .thenThrow(new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "商品所属店铺已关闭: 商家商品"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.add(1L, 10L, null, 1));
        assertEquals("商品所属店铺已关闭: 商家商品", ex.getMessage());

        verifyNoInteractions(cartMapper);
    }

    @Test
    void add_zeroQuantity_rejectedBeforeGuard() {
        // 参数校验要排在校验商品之前：数量非法时不该白查一次库，也不该给出与数量无关的提示
        BusinessException ex = assertThrows(BusinessException.class, () -> service.add(1L, 10L, null, 0));
        assertEquals("数量需大于0", ex.getMessage());
        verifyNoInteractions(productPurchaseGuard, cartMapper);
    }

    private Product product(Long id) {
        Product p = new Product();
        p.setId(id);
        p.setName("测试商品");
        p.setStatus(1);
        p.setPrice(new BigDecimal("66.00"));
        p.setStock(10);
        return p;
    }
}
