package com.dsmarket.modules.ai.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 知识条目状态机（ds_ai_knowledge.status，String 落库）。
 *
 * <p>流转（见 DECISION-20260908-C2）：create/编辑强制 draft；draft/disabled→published 必须经
 * {@code publish}（服务端同步向量化成功才置 published）；published→disabled 仅停用。</p>
 */
@Getter
@AllArgsConstructor
public enum AiKnowledgeStatus {

    DRAFT("draft", "草稿"),
    PUBLISHED("published", "已发布"),
    DISABLED("disabled", "已停用");

    public static final String DRAFT_VALUE = "draft";
    public static final String PUBLISHED_VALUE = "published";
    public static final String DISABLED_VALUE = "disabled";

    private final String value;
    private final String displayName;

    public static AiKnowledgeStatus fromValueOrNull(String value) {
        if (value == null) {
            return null;
        }
        for (AiKnowledgeStatus s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        return null;
    }
}
