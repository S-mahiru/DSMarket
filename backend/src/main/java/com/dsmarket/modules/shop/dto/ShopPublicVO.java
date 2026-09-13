package com.dsmarket.modules.shop.dto;

import com.dsmarket.modules.shop.entity.Shop;
import lombok.Data;

/**
 * 公开店铺 VO（REQ-20260913 §6.2 / §8.2）—— <b>只允许这四个字段</b>。
 *
 * <p><b>绝不能复用 {@link ShopVO}</b>：后者含 {@code userId}（泄漏商家账号）、{@code status}、
 * {@code statusName}、{@code auditRemark}（驳回理由）；{@code ShopAdminVO} 还含 {@code ownerUsername}。
 * {@code ShopVO.from()} 是<b>无差别全字段复制</b>，拿它当公开返回体就是一次信息泄漏 ——
 * 而且泄漏得很安静：接口照常 200，谁也不会注意到响应里多了一个商家账号。</p>
 *
 * <p>往这里加字段前先问一句：这个字段真的可以给<b>匿名访客</b>看吗。</p>
 */
@Data
public class ShopPublicVO {

    private Long id;
    private String shopName;
    private String logo;
    private String description;

    public static ShopPublicVO from(Shop shop) {
        ShopPublicVO vo = new ShopPublicVO();
        vo.setId(shop.getId());
        vo.setShopName(shop.getShopName());
        vo.setLogo(shop.getLogo());
        vo.setDescription(shop.getDescription());
        return vo;
    }
}
