package com.dsmarket.modules.ai.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识条目视图（后台列表/详情）。含状态与类目的中文展示名，以及向量化标记（列表经批量查询填充）。
 */
@Data
public class AiKnowledgeVO {

    private Long id;
    private String category;
    private String categoryName;
    private String question;
    private String answer;
    private List<String> keywords;
    private Boolean faq;
    private String status;
    private String statusName;
    private Boolean embeddingFilled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
