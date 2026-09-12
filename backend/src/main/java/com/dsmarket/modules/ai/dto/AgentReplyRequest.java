package com.dsmarket.modules.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 坐席回复请求体（C4 §4.5 回复 / §4.4 坐席 → 买家）。
 *
 * <p>上限 4000（§3.2），由 Bean Validation 在控制器入口拦掉，服务层再按
 * {@code ai.support.agent-content-max} 复核一次 —— 两处都做是因为服务层也可能被定时任务/内部调用，
 * 而配置是可调的，校验不能只信注解上的常量。</p>
 */
@Data
public class AgentReplyRequest {

    @NotBlank(message = "回复内容不能为空")
    @Size(max = 4000, message = "回复内容过长（最多 4000 字）")
    private String content;

    /** 去除首尾空白后的正文（服务层一律用这个值落库，避免存进纯空格） */
    public String normalizedContent() {
        return content == null ? null : content.trim();
    }
}
