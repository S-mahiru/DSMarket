package com.dsmarket.modules.address.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsmarket.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dsm_address")
public class Address extends BaseEntity {

    private Long userId;
    private String receiverName;
    private String receiverPhone;
    private String province;
    private String city;
    private String district;
    private String detailAddress;
    private String zipCode;
    private String label;
    private Integer isDefault;
}
