package com.dsmarket.modules.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsmarket.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 人工客服消息表（C4 / REQ-20260907-C4 §3.2），映射 {@code ds_ai_support_message}。
 *
 * <p>转人工后消息<b>一律落 PG</b>（坐席可能异步回复 / 在别的工作台），不依赖 Redis 会话
 * —— Redis {@code ai:session:*} 仍只服务 AI 态。人工态消息不经 LLM，AI 态消息也不得写本表。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ds_ai_support_message")
public class AiSupportMessage extends BaseEntity {

    /** 所属人工会话 ds_ai_support_session.id */
    private Long sessionId;

    /** 发送方（大写）：USER / AGENT / SYSTEM */
    private String sender;

    /** 消息正文：买家 ≤2000，坐席 ≤4000（应用层校验） */
    private String content;

    /** 坐席是否已读（工作台红点）；仅内部使用，不回推买家、不外露（J 决议） */
    private Boolean readByAgent;
}
