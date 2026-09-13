package com.dsmarket.modules.shop.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AuditShopRequest {

    /**
     * 1通过 2驳回。
     *
     * <p><b>关闭不走这里</b>（2026-09-13，REQ-20260913-店铺关闭能力 §4.1）：关闭的前置条件是
     * 「已开通」而审核的前置条件是「待审核」，两者塞进同一个校验分支正是当初关不掉店的成因。
     * 关闭请用 {@link CloseShopRequest} + {@code POST /api/v1/admin/shops/{id}/close}。</p>
     */
    @NotNull(message = "审核结果不能为空")
    private Integer status;

    @Size(max = 500, message = "审核备注不能超过500字符")
    private String auditRemark;
}
