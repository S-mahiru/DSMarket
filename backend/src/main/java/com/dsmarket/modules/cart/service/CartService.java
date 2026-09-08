package com.dsmarket.modules.cart.service;

import com.dsmarket.modules.cart.dto.CartItemVO;

import java.util.List;

public interface CartService {

    void add(Long userId, Long productId, Long skuId, Integer quantity);

    List<CartItemVO> list(Long userId);

    void updateQuantity(Long userId, Long cartId, Integer quantity);

    void updateChecked(Long userId, Long cartId, Integer checked);

    void checkAll(Long userId, Integer checked);

    void remove(Long userId, Long cartId);

    void clearChecked(Long userId);

    int count(Long userId);
}
