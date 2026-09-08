package com.dsmarket.modules.ai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 开发调试会话请求（临时端点使用，非最终 SSE 契约）。
 */
@Data
public class DevChatRequest {

    @NotBlank(message = "消息不能为空")
    private String message;
}
