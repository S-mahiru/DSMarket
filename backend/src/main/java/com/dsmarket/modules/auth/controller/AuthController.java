package com.dsmarket.modules.auth.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.modules.auth.dto.LoginRequest;
import com.dsmarket.modules.auth.dto.LoginResponse;
import com.dsmarket.modules.auth.dto.RegisterRequest;
import com.dsmarket.modules.auth.service.AuthService;
import com.dsmarket.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;
    /**
     * 来源 IP 的解析放在控制器层：信任边界（{@code security.client-ip.*}）是<b>接入</b>侧的事，
     * 服务层只管拿一个已经解析好的字符串（审计 §2.4）。
     */
    private final ClientIpResolver clientIpResolver;

    @PostMapping("/register")
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request,
                                      HttpServletRequest httpRequest) {
        authService.register(request, clientIpResolver.resolve(httpRequest));
        return ApiResponse.success();
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest) {
        return ApiResponse.success(authService.login(request, clientIpResolver.resolve(httpRequest)));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            authService.logout(header.substring(BEARER_PREFIX.length()));
        }
        return ApiResponse.success();
    }
}
