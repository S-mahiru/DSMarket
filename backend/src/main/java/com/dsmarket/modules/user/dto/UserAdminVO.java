package com.dsmarket.modules.user.dto;

import com.dsmarket.modules.user.entity.User;
import lombok.Data;

import java.time.LocalDateTime;

/** 管理端用户列表 VO（不含密码） */
@Data
public class UserAdminVO {

    private Long id;
    private String username;
    private String nickname;
    private String email;
    private String phone;
    private String role;
    private Integer status;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createdAt;

    public static UserAdminVO from(User user) {
        UserAdminVO vo = new UserAdminVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        vo.setPhone(user.getPhone());
        vo.setRole(user.getRole());
        vo.setStatus(user.getStatus());
        vo.setLastLoginTime(user.getLastLoginTime());
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }
}
