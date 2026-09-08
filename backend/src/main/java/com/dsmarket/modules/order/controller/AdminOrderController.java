package com.dsmarket.modules.order.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.modules.order.dto.AdminOrderListVO;
import com.dsmarket.modules.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderService orderService;

    /** 全部订单分页（状态/关键字过滤，含买家名） */
    @GetMapping
    public ApiResponse<PageResult<AdminOrderListVO>> page(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResponse.success(orderService.adminPage(status, keyword, page, size));
    }

    /** 发货（仅已付款 → 已发货） */
    @PostMapping("/{orderNo}/ship")
    public ApiResponse<Void> ship(@PathVariable String orderNo) {
        orderService.ship(orderNo);
        return ApiResponse.success();
    }
}
