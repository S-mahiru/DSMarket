package com.dsmarket.modules.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsmarket.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 人工客服会话主表（C4 / REQ-20260907-C4 §3.1），映射 {@code ds_ai_support_session}。
 *
 * <p>约定同 {@link AiKnowledge}/{@link AiIssuePool}：表前缀 {@code ds_ai_} 非全局 {@code dsm_}，
 * 须显式 {@link TableName}；{@code status/origin} 用 String 落库（需求口径）。</p>
 *
 * <p><b>本表只在"转人工发起后"才有行，起始即 pending_human</b>：AI 态的 {@code ai_active}
 * 是隐含态、上下文在 Redis，<b>禁止</b>为本表写入 ai_active 行（§3.1 / §5 图注）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ds_ai_support_session")
public class AiSupportSession extends BaseEntity {

    /** 发起转人工的买家（R2；服务端从 JWT 取，请求体不带） */
    private Long userId;

    /** 状态（小写蛇形）：pending_human / human_active / message_left / closed */
    private String status;

    /** 触发途径（大写）：USER_REQUEST / ANCHOR_HIT / AI_SUGGEST */
    private String origin;

    /** 转人工发起时间 */
    private LocalDateTime requestedAt;

    /** 坐席接入时间（可空） */
    private LocalDateTime activeAt;

    /** 结束时间（可空）；closed 仅由坐席显式结束触发，回复不自动关（P2） */
    private LocalDateTime closedAt;

    /** 最近消息时间（工作台列表倒序排序用；应用层维护，无触发器） */
    private LocalDateTime lastMsgAt;
}
