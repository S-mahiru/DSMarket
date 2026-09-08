package com.dsmarket.modules.user.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.common.util.SecurityUtils;
import com.dsmarket.modules.user.dto.UserAdminVO;
import com.dsmarket.modules.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;

    /** 用户分页（关键词：用户名/昵称/手机号） */
    @GetMapping
    public ApiResponse<PageResult<UserAdminVO>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResponse.success(userService.adminPage(keyword, page, size));
    }

    /** 启用/禁用用户（禁止操作自己） */
    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        userService.adminUpdateStatus(SecurityUtils.requireUserId(), id, status);
        return ApiResponse.success();
    }
}
