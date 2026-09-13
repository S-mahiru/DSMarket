package com.dsmarket.modules.shop.service;

import com.dsmarket.common.domain.PageResult;
import com.dsmarket.modules.shop.dto.ApplyShopRequest;
import com.dsmarket.modules.shop.dto.AuditShopRequest;
import com.dsmarket.modules.shop.dto.CloseShopRequest;
import com.dsmarket.modules.shop.dto.ShopAdminVO;
import com.dsmarket.modules.shop.dto.ShopPublicVO;
import com.dsmarket.modules.shop.dto.ShopVO;

public interface ShopService {

    /** 提交入驻申请（若之前被驳回，重新提交会覆盖旧申请并重置为待审核） */
    ShopVO apply(Long userId, ApplyShopRequest request);

    /** 我的店铺（未入驻返回 null） */
    ShopVO getMine(Long userId);

    /**
     * 前台：公开店铺信息（无需登录）。
     *
     * <p>不存在 / 未开通（{@code status != 1}）/ 已软删 → <b>404</b>，三者不可区分（§8.3）。</p>
     */
    ShopPublicVO getPublicShop(Long shopId);

    /** 管理端：店铺分页（状态/关键词过滤） */
    PageResult<ShopAdminVO> adminPage(Integer status, String keyword, long page, long size);

    /** 管理端：审核店铺（1通过→开通+商家角色；2驳回） */
    void adminAudit(Long shopId, AuditShopRequest request);

    /**
     * 管理端：关闭一个**已开通**的店铺（REQ-20260913-店铺关闭能力）。
     *
     * <p>关闭是<b>终局</b>：{@code status → 3}，不可重开、不可重新申请（Q2 拍板）。
     * 商品的不可见由读写侧既有判据自动生效，本方法<b>不</b>触碰 `dsm_product`、
     * <b>不</b>改商家 `role`（Q3 拍板：不降）、<b>不</b>改任何订单状态。</p>
     *
     * <p>不存在 → 404；非「已开通」状态 → 409（关闭已关闭的、或去关闭一个还在待审核的，
     * 都是状态不合法 —— 后者该走 {@link #adminAudit} 的驳回）。</p>
     */
    void closeShop(Long shopId, CloseShopRequest request);
}
