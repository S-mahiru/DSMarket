package com.dsmarket.modules.ai.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 问题池状态机（ds_ai_issue_pool.status，String 落库，C3 §2）。
 *
 * <p>流转：{@code pending → adopted}（采纳写知识）或 {@code pending → ignored}（忽略）。
 * 终态不可再流转；复核并发用行锁 + CAS 迁移保证只采纳一次。</p>
 */
@Getter
@AllArgsConstructor
public enum AiIssueStatus {

    PENDING("pending", "待处理"),
    ADOPTED("adopted", "已采纳"),
    IGNORED("ignored", "已忽略");

    public static final String PENDING_VALUE = "pending";
    public static final String ADOPTED_VALUE = "adopted";
    public static final String IGNORED_VALUE = "ignored";

    private final String value;
    private final String displayName;

    public static AiIssueStatus fromValueOrNull(String value) {
        if (value == null) {
            return null;
        }
        for (AiIssueStatus s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        return null;
    }
}
