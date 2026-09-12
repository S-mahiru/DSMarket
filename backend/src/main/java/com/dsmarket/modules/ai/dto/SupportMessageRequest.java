package com.dsmarket.modules.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 人工态发消息请求（REQ C4 §4.4）：{@code POST /api/v1/ai/support/message}。
 *
 * <p>上限 2000（§3.2），<b>不是</b> /chat 的 500 —— 人工通道存在的意义就是让买家把完整申诉说清楚，
 * 沿用 500 会把长投诉截断在门口。</p>
 *
 * <p>校验语义同 {@link ChatRequest}：Bean 校验只卡上限（&gt;2000 → 400），"是否只有空白"留给
 * 服务层用 {@link #normalizedContent()} 判空后回 400 —— 这样"   "能给出更准确的提示语。</p>
 */
@Data
public class SupportMessageRequest {

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 2000, message = "消息过长（最多 2000 字）")
    private String content;

    /** 幂等键（可选）：同 userId+sessionId+clientMsgId 在 5s 内重复 → 200 不重落库（§4.4 P6） */
    @Size(max = 64, message = "clientMsgId 过长")
    private String clientMsgId;

    /** 服务端使用的原文（已 trim） */
    public String normalizedContent() {
        return content == null ? "" : content.trim();
    }
}
