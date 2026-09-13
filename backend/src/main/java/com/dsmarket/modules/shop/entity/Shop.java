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
    /**
     * 状态。取值语义见 {@link com.dsmarket.common.enums.ShopStatusEnum}（唯一事实来源）：
     * 0待审核 / 1已开通 / 2已驳回（可重新申请） / 3已关闭（终局）。
     *
     * <p>列上的 COMMENT 仍写着旧语义「2已关闭/驳回」——那是 `V4__merchant_shop.sql` 里的历史
     * 迁移，**不回改**（已执行过的迁移改了也不会重放，且改它等于篡改历史）。以本注释与枚举为准。</p>
     */
    private Integer status;
    /** 审核备注（ALWAYS：update 时总是写入，包括置空——驳回后重新提交需清空旧备注） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String auditRemark;
}
