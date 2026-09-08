package com.dsmarket.modules.shop.dto;

import com.dsmarket.modules.shop.entity.Shop;
import lombok.Data;

import java.time.LocalDateTime;

/** 商家侧我的店铺 VO */
@Data
public class ShopVO {

    private Long id;
    private Long userId;
    private String shopName;
    private String logo;
    private String description;
    private Integer status;
    private String statusName;
    private String auditRemark;
    private LocalDateTime createdAt;

    public static ShopVO from(Shop shop) {
        ShopVO vo = new ShopVO();
        vo.setId(shop.getId());
        vo.setUserId(shop.getUserId());
        vo.setShopName(shop.getShopName());
        vo.setLogo(shop.getLogo());
        vo.setDescription(shop.getDescription());
        vo.setStatus(shop.getStatus());
        vo.setStatusName(statusName(shop.getStatus()));
        vo.setAuditRemark(shop.getAuditRemark());
        vo.setCreatedAt(shop.getCreatedAt());
        return vo;
    }

    public static String statusName(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 0 -> "待审核";
            case 1 -> "已开通";
            case 2 -> "已驳回/关闭";
            default -> "未知";
        };
    }
}
