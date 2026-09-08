package com.dsmarket.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum UserRoleEnum {

    USER("USER", "普通用户"),
    MERCHANT("MERCHANT", "商家"),
    ADMIN("ADMIN", "管理员");

    private final String value;
    private final String displayName;
}
