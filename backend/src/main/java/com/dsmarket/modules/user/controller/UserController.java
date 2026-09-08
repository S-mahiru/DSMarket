package com.dsmarket.modules.user.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.common.util.SecurityUtils;
import com.dsmarket.modules.user.dto.UpdatePasswordRequest;
import com.dsmarket.modules.user.dto.UpdateProfileRequest;
import com.dsmarket.modules.user.dto.UserVO;
import com.dsmarket.modules.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/profile")
    public ApiResponse<UserVO> getProfile() {
        return ApiResponse.success(userService.getProfile(SecurityUtils.requireUserId()));
    }

    @PutMapping("/profile")
    public ApiResponse<UserVO> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.success(userService.updateProfile(SecurityUtils.requireUserId(), request));
    }

    @PutMapping("/password")
    public ApiResponse<Void> updatePassword(@Valid @RequestBody UpdatePasswordRequest request) {
        userService.updatePassword(SecurityUtils.requireUserId(), request);
        return ApiResponse.success();
    }
}
