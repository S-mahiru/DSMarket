package com.dsmarket.modules.ai.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.dsmarket.common.config.StringArrayTypeHandler;
import com.dsmarket.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 客服知识库条目（C2 / 主 REQ F6），映射 {@code ds_ai_knowledge}。
 *
 * <p>约定（见 DECISION-20260908-C2）：表前缀 {@code ds_ai_} 非全局 {@code dsm_}，须显式
 * {@link TableName}；{@code embedding} 列不进本实体（类型 vector(1024) 由 Mapper 自定义 SQL 写入）；
 * {@code status} 用 String（draft/published/disabled，需求口径，非 SMALLINT）；keywords 为 varchar[]。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ds_ai_knowledge")
public class AiKnowledge extends BaseEntity {

    /** 类目：after_sale/shipping/payment/product_policy/account/other */
    private String category;

    /** 标准问题（检索对象之一，应用层 ≤500） */
    private String question;

    /** 标准答案（应用层 ≤2000） */
    private String answer;

    /** 可检索别名 */
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private String[] keywords;

    /** true=参与 FAQ 兜底精选 */
    private Boolean faq;

    /** 状态：draft / published / disabled（published 才有 embedding，检索只读它） */
    private String status;

    /**
     * Dense 余弦距离（pgvector {@code embedding <=> query_vec}，见 searchDense）。
     * 仅检索路经 Mapper 出列回填，<b>不落库</b>（{@code exist=false}，BaseMapper 忽略）；
     * 非 Dense 来源行为 null。幅度闸（ConfidenceGate #3）/评估（routes）数据源。
     */
    @TableField(exist = false)
    private Double denseDistance;
}
