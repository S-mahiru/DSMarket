package com.dsmarket.modules.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单回合 AI 客服结果（供展示/留痕）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatTurnResult {

    /** 最终给用户的话术 */
    private String reply;

    /** 本回合实际调用过的工具名（顺序），留痕/审计用 */
    private List<String> toolsUsed;

    /** 本轮回执编号（会话层签发，用户内唯一；前端绑定气泡，供点赞/点踩回查） */
    private String replyId;
}
