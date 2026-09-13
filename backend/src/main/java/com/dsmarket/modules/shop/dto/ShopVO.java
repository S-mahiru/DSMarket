package com.dsmarket.modules.shop.dto;

import com.dsmarket.common.enums.ShopStatusEnum;
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

    /**
     * 状态展示名。委托给 {@link ShopStatusEnum} —— 那里是 status 语义的唯一事实来源。
     *
     * <p>2026-09-13（REQ-20260913-店铺关闭能力 §4.2 方案甲）：原先 {@code 2} 显示为
     * 「已驳回/关闭」，把两个语义压在一个值上。现在拆开：{@code 2} 只是「已驳回」，
     * 「已关闭」是新值 {@code 3} —— 于是"被关闭的店能靠重新申请复活"那个坑不复存在。</p>
     */
    public static String statusName(Integer status) {
        return ShopStatusEnum.nameOf(status);
    }
}
