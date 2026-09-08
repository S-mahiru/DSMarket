package com.dsmarket.modules.cart.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.cart.dto.CartItemVO;
import com.dsmarket.modules.cart.entity.Cart;
import com.dsmarket.modules.cart.mapper.CartMapper;
import com.dsmarket.modules.cart.service.CartService;
import com.dsmarket.modules.product.dto.SpecItem;
import com.dsmarket.modules.product.entity.Product;
import com.dsmarket.modules.product.entity.ProductSku;
import com.dsmarket.modules.product.mapper.ProductMapper;
import com.dsmarket.modules.product.mapper.ProductSkuMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private final CartMapper cartMapper;
    private final ProductMapper productMapper;
    private final ProductSkuMapper productSkuMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void add(Long userId, Long productId, Long skuId, Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "数量需大于0");
        }
        Cart existing = cartMapper.selectOne(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getUserId, userId)
                .eq(Cart::getProductId, productId)
                .eq(Cart::getSkuId, skuId));
        if (existing != null) {
            existing.setQuantity(existing.getQuantity() + quantity);
            cartMapper.updateById(existing);
            return;
        }
        Cart cart = new Cart();
        cart.setUserId(userId);
        cart.setProductId(productId);
        cart.setSkuId(skuId);
        cart.setQuantity(quantity);
        cart.setChecked(1);
        cartMapper.insert(cart);
    }

    @Override
    public List<CartItemVO> list(Long userId) {
        List<Cart> carts = cartMapper.selectList(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getUserId, userId).orderByDesc(Cart::getId));

        List<Long> productIds = carts.stream().map(Cart::getProductId).distinct().toList();
        List<Long> skuIds = carts.stream().map(Cart::getSkuId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, Product> productMap = productIds.isEmpty() ? Map.of()
                : productMapper.selectBatchIds(productIds).stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, ProductSku> skuMap = skuIds.isEmpty() ? Map.of()
                : productSkuMapper.selectBatchIds(skuIds).stream().collect(Collectors.toMap(ProductSku::getId, Function.identity()));

        List<CartItemVO> result = new ArrayList<>();
        for (Cart cart : carts) {
            Product product = productMap.get(cart.getProductId());
            ProductSku sku = cart.getSkuId() != null ? skuMap.get(cart.getSkuId()) : null;
            if (product == null) {
                continue;
            }
            CartItemVO vo = new CartItemVO();
            vo.setId(cart.getId());
            vo.setProductId(product.getId());
            vo.setProductName(product.getName());
            vo.setProductImage(product.getMainImage());
            vo.setSkuId(cart.getSkuId());
            vo.setSkuSpecs(sku != null ? parseSpecs(sku.getSpecs()) : List.of());
            BigDecimal unitPrice = sku != null && sku.getPrice() != null ? sku.getPrice() : product.getPrice();
            vo.setUnitPrice(unitPrice);
            vo.setQuantity(cart.getQuantity());
            vo.setSubtotal(unitPrice.multiply(BigDecimal.valueOf(cart.getQuantity())));
            vo.setStock(sku != null && sku.getStock() != null ? sku.getStock() : product.getStock());
            vo.setChecked(cart.getChecked());
            result.add(vo);
        }
        return result;
    }

    @Override
    public void updateQuantity(Long userId, Long cartId, Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "数量需大于0");
        }
        Cart cart = requireOwned(userId, cartId);
        cart.setQuantity(quantity);
        cartMapper.updateById(cart);
    }

    @Override
    public void updateChecked(Long userId, Long cartId, Integer checked) {
        Cart cart = requireOwned(userId, cartId);
        cart.setChecked(checked == null ? 0 : checked);
        cartMapper.updateById(cart);
    }

    @Override
    public void checkAll(Long userId, Integer checked) {
        Cart update = new Cart();
        update.setChecked(checked == null ? 0 : checked);
        cartMapper.update(update, new LambdaQueryWrapper<Cart>().eq(Cart::getUserId, userId));
    }

    @Override
    public void remove(Long userId, Long cartId) {
        requireOwned(userId, cartId);
        cartMapper.deleteById(cartId);
    }

    @Override
    @Transactional
    public void clearChecked(Long userId) {
        cartMapper.delete(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getUserId, userId).eq(Cart::getChecked, 1));
    }

    @Override
    public int count(Long userId) {
        List<Cart> carts = cartMapper.selectList(new LambdaQueryWrapper<Cart>().eq(Cart::getUserId, userId));
        return carts.stream().mapToInt(c -> c.getQuantity() == null ? 0 : c.getQuantity()).sum();
    }

    private Cart requireOwned(Long userId, Long cartId) {
        Cart cart = cartMapper.selectById(cartId);
        if (cart == null || !cart.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "购物车项不存在");
        }
        return cart;
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
}
