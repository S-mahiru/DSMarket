package com.dsmarket.modules.ai.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 人工消息发送方（{@code ds_ai_support_message.sender}，C4 §3.2）。
 *
 * <p><b>大写常量</b>（§3 枚举大小写规范）。SYSTEM 用于系统提示落库 —— 典型是 30s 无人接的
 * "当前坐席繁忙，可留言或稍后再试"，买家 SSE 断开时靠它保证语义不丢（H28 回读兜底）。</p>
 */
@Getter
@AllArgsConstructor
public enum AiSupportSender {

    USER("USER", "买家"),
    AGENT("AGENT", "坐席"),
    SYSTEM("SYSTEM", "系统");

    public static final String USER_VALUE = "USER";
    public static final String AGENT_VALUE = "AGENT";
    public static final String SYSTEM_VALUE = "SYSTEM";

    private final String value;
    private final String displayName;

    public static AiSupportSender fromValueOrNull(String value) {
        if (value == null) {
            return null;
        }
        for (AiSupportSender s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        return null;
    }
}
