package com.dsmarket.modules.shop.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.modules.shop.dto.AuditShopRequest;
import com.dsmarket.modules.shop.dto.ShopAdminVO;
import com.dsmarket.modules.shop.service.ShopService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/shops")
@RequiredArgsConstructor
public class AdminShopController {

    private final ShopService shopService;

    /** 店铺分页（状态：0待审核/1已开通/2已驳回/关闭；关键词：店铺名/商家名） */
    @GetMapping
    public ApiResponse<PageResult<ShopAdminVO>> page(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResponse.success(shopService.adminPage(status, keyword, page, size));
    }

    /** 审核店铺（1通过→开通商家；2驳回） */
    @PostMapping("/{id}/audit")
    public ApiResponse<Void> audit(@PathVariable Long id, @Valid @RequestBody AuditShopRequest request) {
        shopService.adminAudit(id, request);
        return ApiResponse.success();
    }
}
