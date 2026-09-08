package com.dsmarket.modules.cart.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.common.util.SecurityUtils;
import com.dsmarket.modules.cart.dto.CartItemVO;
import com.dsmarket.modules.cart.service.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    public ApiResponse<List<CartItemVO>> list() {
        return ApiResponse.success(cartService.list(SecurityUtils.requireUserId()));
    }

    @PostMapping
    public ApiResponse<Void> add(@RequestBody Map<String, Object> body) {
        Long productId = Long.valueOf(String.valueOf(body.get("productId")));
        Long skuId = body.get("skuId") != null ? Long.valueOf(String.valueOf(body.get("skuId"))) : null;
        Integer quantity = body.get("quantity") != null ? Integer.valueOf(String.valueOf(body.get("quantity"))) : 1;
        cartService.add(SecurityUtils.requireUserId(), productId, skuId, quantity);
        return ApiResponse.success();
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> updateQuantity(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer quantity = Integer.valueOf(String.valueOf(body.get("quantity")));
        cartService.updateQuantity(SecurityUtils.requireUserId(), id, quantity);
        return ApiResponse.success();
    }

    @PutMapping("/{id}/check")
    public ApiResponse<Void> updateChecked(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Integer checked = Integer.valueOf(String.valueOf(body.get("checked")));
        cartService.updateChecked(SecurityUtils.requireUserId(), id, checked);
        return ApiResponse.success();
    }

    @PutMapping("/check-all")
    public ApiResponse<Void> checkAll(@RequestBody Map<String, Object> body) {
        Integer checked = Integer.valueOf(String.valueOf(body.get("checked")));
        cartService.checkAll(SecurityUtils.requireUserId(), checked);
        return ApiResponse.success();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        cartService.remove(SecurityUtils.requireUserId(), id);
        return ApiResponse.success();
    }

    @DeleteMapping("/clear")
    public ApiResponse<Void> clearChecked() {
        cartService.clearChecked(SecurityUtils.requireUserId());
        return ApiResponse.success();
    }

    @GetMapping("/count")
    public ApiResponse<Map<String, Integer>> count() {
        return ApiResponse.success(Map.of("count", cartService.count(SecurityUtils.requireUserId())));
    }
}
