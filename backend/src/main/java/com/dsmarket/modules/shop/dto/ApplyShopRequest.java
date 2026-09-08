package com.dsmarket.modules.shop.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ApplyShopRequest {

    @NotBlank(message = "店铺名称不能为空")
    @Size(max = 100, message = "店铺名称不能超过100字符")
    private String shopName;

    @Size(max = 500, message = "Logo地址不能超过500字符")
    private String logo;

    @Size(max = 500, message = "店铺介绍不能超过500字符")
    private String description;
}
