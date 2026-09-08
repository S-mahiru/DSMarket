package com.dsmarket.modules.order.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.common.util.SecurityUtils;
import com.dsmarket.modules.order.dto.PaymentVO;
import com.dsmarket.modules.order.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/pay")
    public ApiResponse<PaymentVO> pay(@RequestBody Map<String, Object> body) {
        String orderNo = String.valueOf(body.get("orderNo"));
        String method = body.get("paymentMethod") != null ? String.valueOf(body.get("paymentMethod")) : "MOCK";
        return ApiResponse.success(paymentService.pay(SecurityUtils.requireUserId(), orderNo, method));
    }

    @GetMapping("/status/{orderNo}")
    public ApiResponse<PaymentVO> status(@PathVariable String orderNo) {
        return ApiResponse.success(paymentService.status(SecurityUtils.requireUserId(), orderNo));
    }
}
