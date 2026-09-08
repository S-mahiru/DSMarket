package com.dsmarket.modules.order.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.common.util.SecurityUtils;
import com.dsmarket.modules.order.dto.CancelOrderRequest;
import com.dsmarket.modules.order.dto.CreateOrderRequest;
import com.dsmarket.modules.order.dto.OrderCreateResult;
import com.dsmarket.modules.order.dto.OrderDetailVO;
import com.dsmarket.modules.order.dto.OrderListVO;
import com.dsmarket.modules.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ApiResponse<OrderCreateResult> create(@RequestBody CreateOrderRequest request) {
        return ApiResponse.success(orderService.create(SecurityUtils.requireUserId(), request));
    }

    @GetMapping
    public ApiResponse<PageResult<OrderListVO>> list(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResponse.success(orderService.list(SecurityUtils.requireUserId(), status, page, size));
    }

    @GetMapping("/{orderNo}")
    public ApiResponse<OrderDetailVO> detail(@PathVariable String orderNo) {
        return ApiResponse.success(orderService.detail(SecurityUtils.requireUserId(), orderNo));
    }

    @PostMapping("/{orderNo}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String orderNo,
                                    @RequestBody(required = false) CancelOrderRequest request) {
        orderService.cancel(SecurityUtils.requireUserId(), orderNo,
                request == null ? null : request.getCancelReason());
        return ApiResponse.success();
    }

    @PostMapping("/{orderNo}/receive")
    public ApiResponse<Void> receive(@PathVariable String orderNo) {
        orderService.receive(SecurityUtils.requireUserId(), orderNo);
        return ApiResponse.success();
    }
}
