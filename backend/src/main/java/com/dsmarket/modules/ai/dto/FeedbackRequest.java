package com.dsmarket.modules.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 买家反馈请求（主 REQ §2.4 feedback / C3-F2）。replyId 为当轮 AI 回答 done 事件下发值；
 * 前端把它绑定在回复气泡的 👍/👎 上。
 */
@Data
public class FeedbackRequest {

    /** 当轮回复 id（done 签发，会话内唯一）；点踩回查用 */
    @NotBlank(message = "replyId 不能为空")
    @Size(max = 64, message = "replyId 不合法")
    private String replyId;

    /** true=点赞（仅日志不采集）；false=点踩（回查原问句入问题池） */
    @NotNull(message = "satisfied 不能为空")
    private Boolean satisfied;

    /** 可选：补充理由（点赞/点踩均可带，仅日志） */
    @Size(max = 200, message = "理由不能超过200字")
    private String reason;
}
