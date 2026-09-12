package com.dsmarket.modules.ai.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 买家侧会话快照（C4 §4.4 会话恢复，<b>历史的唯一来源</b>）。
 *
 * <p>{@code GET /api/v1/ai/support/session} 返回本对象；SSE 只推连接期间的新事件、<b>不重放历史</b>
 * —— 所以前端每次打开抽屉都必须先拉它，否则会丢断线期间的消息（H20/H21）。</p>
 *
 * <p>{@code exists=false} 表示该买家没有人工会话行（纯 AI 态），此时其余字段为 null、messages 为空；
 * 前端据此只显示"转人工"入口，不残留旧 UI（H23）。<b>没有独立的 active/closed 布尔</b>：
 * 终态一律由 {@code status=closed} 表达（§4.4 权威 I 决议）。</p>
 */
@Data
public class AiSupportSessionVO {

    /** 是否存在人工会话行（false = 纯 AI 态） */
    private boolean exists;

    /** pending_human / human_active / message_left / closed；exists=false 时为 null */
    private String status;

    /** 已结束/已留言等状态的中文名，供前端直接展示 */
    private String statusName;

    private Long sessionId;

    private LocalDateTime requestedAt;

    private LocalDateTime activeAt;

    /** 仅 status=closed 时有值 */
    private LocalDateTime closedAt;

    /** 全量消息（id 正序 = 时间正序）；exists=false 时为空列表 */
    private List<AiSupportMessageVO> messages;
}
