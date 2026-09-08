package com.dsmarket.modules.shop.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AuditShopRequest {

    /** 1通过 2驳回/关闭 */
    @NotNull(message = "审核结果不能为空")
    private Integer status;

    @Size(max = 500, message = "审核备注不能超过500字符")
    private String auditRemark;
}
