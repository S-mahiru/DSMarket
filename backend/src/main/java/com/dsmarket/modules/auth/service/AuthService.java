package com.dsmarket.modules.auth.service;

import com.dsmarket.modules.auth.dto.LoginRequest;
import com.dsmarket.modules.auth.dto.LoginResponse;
import com.dsmarket.modules.auth.dto.RegisterRequest;

public interface AuthService {

    /**
     * 注册。{@code clientIp} 用于注册限流（审计 §2.4）。
     *
     * <p>由调用方（控制器）解析后传入，而不是在这里注入 {@code HttpServletRequest}：
     * 服务层不该认识 servlet API，且这样传入的来源 IP 在测试里可以直接指定。</p>
     */
    void register(RegisterRequest request, String clientIp);

    /** 登录。{@code clientIp} 用于失败限流的 IP 维度（审计 §2.4）。 */
    LoginResponse login(LoginRequest request, String clientIp);

    void logout(String token);
}
