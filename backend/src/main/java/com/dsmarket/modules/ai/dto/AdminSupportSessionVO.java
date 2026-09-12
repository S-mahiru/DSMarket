package com.dsmarket.modules.ai.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作台会话列表项（C4 §4.5 三列表）。
 *
 * <p>用户信息默认<b>脱敏</b>（{@code userMasked}）：工作台不做用户详情页，
 * 看完整联系方式是 ADMIN 域内的显式动作（§7 数据最小化），不在列表里默认外露。</p>
 */
@Data
public class AdminSupportSessionVO {

    private Long sessionId;

    /** 脱敏后的用户标识（如 {@code a***t}）；用户已被删除时回落为 {@code 用户#{id}} */
    private String userMasked;

    /** 触发途径：USER_REQUEST / ANCHOR_HIT / AI_SUGGEST */
    private String origin;

    /** pending_human / human_active / message_left / closed */
    private String status;

    private String statusName;

    private LocalDateTime requestedAt;

    private LocalDateTime activeAt;

    private LocalDateTime closedAt;

    private LocalDateTime lastMsgAt;

    /** 未读红点：该会话中买家发出且坐席未读的条数（§4.5） */
    private int unreadCount;
}
