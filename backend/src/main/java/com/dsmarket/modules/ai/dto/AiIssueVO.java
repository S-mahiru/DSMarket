package com.dsmarket.modules.ai.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问题池条目视图（后台列表，仅 R3/ADMIN 可见）。
 *
 * <p>含来源与状态的中文展示名；question 为被采集问句，列表不落业务明细。userId 仅去重/追溯用。</p>
 */
@Data
public class AiIssueVO {

    private Long id;
    private Long userId;
    private String question;
    private String source;
    private String sourceName;
    private String status;
    private String statusName;
    private String replyId;
    private Long relatedKnowledgeId;
    private Integer retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
