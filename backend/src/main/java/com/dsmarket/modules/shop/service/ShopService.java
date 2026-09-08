package com.dsmarket.modules.shop.service;

import com.dsmarket.common.domain.PageResult;
import com.dsmarket.modules.shop.dto.ApplyShopRequest;
import com.dsmarket.modules.shop.dto.AuditShopRequest;
import com.dsmarket.modules.shop.dto.ShopAdminVO;
import com.dsmarket.modules.shop.dto.ShopVO;

public interface ShopService {

    /** 提交入驻申请（若之前被驳回，重新提交会覆盖旧申请并重置为待审核） */
    ShopVO apply(Long userId, ApplyShopRequest request);

    /** 我的店铺（未入驻返回 null） */
    ShopVO getMine(Long userId);

    /** 管理端：店铺分页（状态/关键词过滤） */
    PageResult<ShopAdminVO> adminPage(Integer status, String keyword, long page, long size);

    /** 管理端：审核店铺（1通过→开通+商家角色；2驳回） */
    void adminAudit(Long shopId, AuditShopRequest request);
}
