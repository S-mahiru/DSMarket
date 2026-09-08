package com.dsmarket.modules.shop.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 管理端店铺列表 VO */
@Data
public class ShopAdminVO {

    private Long id;
    private Long userId;
    private String ownerUsername;
    private String shopName;
    private String logo;
    private String description;
    private Integer status;
    private String statusName;
    private String auditRemark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ShopAdminVO from(ShopAdminRow row) {
        ShopAdminVO vo = new ShopAdminVO();
        vo.setId(row.getId());
        vo.setUserId(row.getUserId());
        vo.setOwnerUsername(row.getOwnerUsername());
        vo.setShopName(row.getShopName());
        vo.setLogo(row.getLogo());
        vo.setDescription(row.getDescription());
        vo.setStatus(row.getStatus());
        vo.setStatusName(ShopVO.statusName(row.getStatus()));
        vo.setAuditRemark(row.getAuditRemark());
        vo.setCreatedAt(row.getCreatedAt());
        vo.setUpdatedAt(row.getUpdatedAt());
        return vo;
    }
}
