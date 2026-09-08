package com.dsmarket.modules.auth.service;

import com.dsmarket.modules.auth.dto.LoginRequest;
import com.dsmarket.modules.auth.dto.LoginResponse;
import com.dsmarket.modules.auth.dto.RegisterRequest;

public interface AuthService {

    void register(RegisterRequest request);

    LoginResponse login(LoginRequest request);

    void logout(String token);
}
