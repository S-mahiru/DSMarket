package com.dsmarket.modules.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsmarket.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dsm_user")
public class User extends BaseEntity {

    private String username;
    private String password;
    private String email;
    private String phone;
    private String avatar;
    private String nickname;
    private Integer gender;
    private String role;
    private Integer status;
    private LocalDateTime lastLoginTime;
    private String loginIp;
}
