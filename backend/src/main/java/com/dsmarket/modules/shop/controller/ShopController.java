package com.dsmarket.modules.shop.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.common.util.SecurityUtils;
import com.dsmarket.modules.shop.dto.ApplyShopRequest;
import com.dsmarket.modules.shop.dto.ShopVO;
import com.dsmarket.modules.shop.service.ShopService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shop")
@RequiredArgsConstructor
public class ShopController {

    private final ShopService shopService;

    /** 提交入驻申请（需登录） */
    @PostMapping("/apply")
    public ApiResponse<ShopVO> apply(@Valid @RequestBody ApplyShopRequest request) {
        return ApiResponse.success(shopService.apply(SecurityUtils.requireUserId(), request));
    }

    /** 我的店铺（未入驻返回 data=null） */
    @GetMapping("/mine")
    public ApiResponse<ShopVO> mine() {
        return ApiResponse.success(shopService.getMine(SecurityUtils.requireUserId()));
    }
}
