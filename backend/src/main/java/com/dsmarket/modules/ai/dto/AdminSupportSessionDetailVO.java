package com.dsmarket.modules.ai.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工作台会话详情（C4 §4.5）：完整消息 + AI 对话回放 + 脱敏用户信息。
 *
 * <p><b>不含关联订单摘要</b>：§4.5 列了这一项（只读查询），切片 2 未实现 ——
 * 与其塞一个空壳字段假装有，不如不写，列在 DECISION 的残余里（要做的前提是先确定
 * 工作台按什么键关联订单，REQ 未给）。</p>
 */
@Data
public class AdminSupportSessionDetailVO {

    private Long sessionId;

    private String userMasked;

    private String origin;

    private String status;

    private String statusName;

    private LocalDateTime requestedAt;

    private LocalDateTime activeAt;

    private LocalDateTime closedAt;

    private LocalDateTime lastMsgAt;

    /** 该会话完整消息，{@code id} 正序（= 时间正序）；含接入前缓冲的买家消息（§5 硬规则"接入前消息不丢"） */
    private List<AdminSupportMessageVO> messages;

    /**
     * AI 对话回放（L 决议，原文直读近 N 轮）。<b>空列表 = 无可回放的 AI 对话</b>
     * （Redis 会话已过期或转人工前没聊过），前端据此显示"无 AI 对话记录"，不得编造。
     */
    private List<AiSessionSummaryVO> aiSummary;
}
