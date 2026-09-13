package com.dsmarket.modules.shop.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.modules.shop.dto.AuditShopRequest;
import com.dsmarket.modules.shop.dto.CloseShopRequest;
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

    /** 店铺分页（状态：0待审核/1已开通/2已驳回/3已关闭；关键词：店铺名/商家名） */
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

    /**
     * 关闭已开通的店铺（REQ-20260913-店铺关闭能力 §4.1）。
     *
     * <p><b>为什么不复用 {@code /audit} 传 {@code status=2}</b>：审核的前置条件是"待审核"，
     * 关闭的前置条件是"已开通"，两者塞进同一个校验分支正是当初关不掉店的成因；
     * 且 {@code 2} 在代码里是"驳回后可重新申请"，拿它当关闭会让关闭被一次重新申请撤销。</p>
     *
     * <p>请求体可省（关闭理由可选）。不存在 → 404；非已开通 → 409。</p>
     */
    @PostMapping("/{id}/close")
    public ApiResponse<Void> close(@PathVariable Long id,
                                   @Valid @RequestBody(required = false) CloseShopRequest request) {
        shopService.closeShop(id, request);
        return ApiResponse.success();
    }
}
