package com.dsmarket.modules.ai.dto;

import lombok.Data;

/**
 * 知识后台分页查询条件。
 */
@Data
public class AiKnowledgeQuery {

    /** 关键词：对 question/answer LIKE */
    private String keyword;

    /** 类目精确过滤，可空 */
    private String category;

    /** 状态精确过滤（draft/published/disabled），可空 */
    private String status;
}
