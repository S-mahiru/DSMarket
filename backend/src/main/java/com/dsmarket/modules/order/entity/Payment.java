package com.dsmarket.modules.order.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付记录表（仅追加，无 updated_at / deleted 列）
 */
@Data
@TableName("dsm_payment")
public class Payment {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private Long userId;
    private String paymentNo;
    private String paymentMethod;
    private BigDecimal amount;
    private Integer status;
    private LocalDateTime payTime;
    private String notifyData;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
