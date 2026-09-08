package com.dsmarket.modules.shop.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 管理端店铺分页联查行（Mapper 直出） */
@Data
public class ShopAdminRow {

    private Long id;
    private Long userId;
    private String shopName;
    private String logo;
    private String description;
    private Integer status;
    private String auditRemark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String ownerUsername;
}
