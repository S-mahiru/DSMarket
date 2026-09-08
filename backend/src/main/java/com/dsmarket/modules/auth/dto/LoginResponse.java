package com.dsmarket.modules.auth.dto;

import com.dsmarket.modules.user.dto.UserVO;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponse {

    private String token;
    private String tokenType;
    private long expiresIn;
    private UserVO user;
}
