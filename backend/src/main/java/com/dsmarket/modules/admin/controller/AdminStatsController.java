package com.dsmarket.modules.admin.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.modules.admin.dto.AdminStatsVO;
import com.dsmarket.modules.admin.service.AdminStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/stats")
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminStatsService adminStatsService;

    @GetMapping
    public ApiResponse<AdminStatsVO> stats() {
        return ApiResponse.success(adminStatsService.getStats());
    }
}
