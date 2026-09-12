package com.dsmarket.modules.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.dsmarket.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 客服问题池条目（C3 / 主 REQ F7），映射 {@code ds_ai_issue_pool}。
 *
 * <p>约定同 {@link AiKnowledge}：表前缀 {@code ds_ai_} 非全局 {@code dsm_}，须显式 {@link TableName}；
 * {@code source/status} 用 String 落库（需求口径）。question 为被采集问句（低置信取干净问句 /
 * 点踩取会话原问句），列表仅 R3 可见。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ds_ai_issue_pool")
public class AiIssuePool extends BaseEntity {

    /** 提问人（可空：低置信保留、点踩必填；仅幂等去重与后台追溯用，不落业务明细） */
    private Long userId;

    /** 被采集问句（应用层 ≤500） */
    private String question;

    /** 来源：LOW_CONF（低置信自动）/ DISLIKE（点踩） */
    private String source;

    /** 状态：pending / adopted / ignored（终态不可再流转） */
    private String status;

    /** 采纳后关联知识 id（实施：先建即回填，供 F6 兜底重试定位 draft 行） */
    private Long relatedKnowledgeId;

    /** source=DISLIKE 时记录对应回复 replyId */
    private String replyId;

    /** 向量化兜底重试计数（实施补充列，超上限告警） */
    private Integer retryCount;
}
