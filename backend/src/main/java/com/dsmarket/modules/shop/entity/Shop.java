package com.dsmarket.modules.shop.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.dsmarket.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dsm_shop")
public class Shop extends BaseEntity {

    /** 商家用户ID */
    private Long userId;
    /** 店铺名称 */
    private String shopName;
    /** 店铺Logo URL */
    private String logo;
    /** 店铺介绍 */
    private String description;
    /** 状态:0待审核 1已开通 2已关闭/驳回 */
    private Integer status;
    /** 审核备注（ALWAYS：update 时总是写入，包括置空——驳回后重新提交需清空旧备注） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String auditRemark;
}
