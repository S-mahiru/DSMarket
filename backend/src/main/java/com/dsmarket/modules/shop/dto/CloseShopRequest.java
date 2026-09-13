package com.dsmarket.modules.shop.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 关闭店铺请求（REQ-20260913-店铺关闭能力 §4.1）。
 *
 * <p>只有「关闭理由」一个字段，复用 `dsm_shop.audit_remark` 列 ——
 * 该列的语义是「最近一次管理动作的备注」，通过/驳回/关闭都写它，不新增列、不新增迁移脚本（§6.1）。</p>
 *
 * <p>整个 body 可省（{@code @RequestBody(required = false)}）：关闭动作本身不带参数也成立，
 * 理由只是给商家看的说明。前端会强制填，接口层不强制。</p>
 */
@Data
public class CloseShopRequest {

    @Size(max = 500, message = "关闭理由不能超过500字符")
    private String auditRemark;
}
