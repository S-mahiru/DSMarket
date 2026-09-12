package com.dsmarket.modules.ai.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Set;

/**
 * 转人工触发途径（{@code ds_ai_support_session.origin}，C4 §4.1）。
 *
 * <p><b>大写常量</b>（§3 枚举大小写规范）。三个途径：用户主动点按钮、/chat 入口锚点词命中、
 * AI 建议气泡点击。</p>
 *
 * <p>{@link #ANCHOR_HIT} 由服务端在 /chat 入口内部触发（C4 切片 3），<b>不接受买家接口传入</b>
 * —— 否则买家可自报"我是锚点触发的"来绕过内容长度限制。{@link #PUBLIC_VALUES} 是买家可显式指定的集合。</p>
 */
@Getter
@AllArgsConstructor
public enum AiSupportOrigin {

    USER_REQUEST("USER_REQUEST", "用户主动"),
    ANCHOR_HIT("ANCHOR_HIT", "锚点命中"),
    AI_SUGGEST("AI_SUGGEST", "建议点击");

    public static final String USER_REQUEST_VALUE = "USER_REQUEST";
    public static final String ANCHOR_HIT_VALUE = "ANCHOR_HIT";
    public static final String AI_SUGGEST_VALUE = "AI_SUGGEST";

    /** 买家可经 {@code POST /support/request} 显式指定的途径（ANCHOR_HIT 仅服务端内部用） */
    public static final Set<String> PUBLIC_VALUES = Set.of(USER_REQUEST_VALUE, AI_SUGGEST_VALUE);

    private final String value;
    private final String displayName;

    public static AiSupportOrigin fromValueOrNull(String value) {
        if (value == null) {
            return null;
        }
        for (AiSupportOrigin o : values()) {
            if (o.value.equals(value)) {
                return o;
            }
        }
        return null;
    }
}
