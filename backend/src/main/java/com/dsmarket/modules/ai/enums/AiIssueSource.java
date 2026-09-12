package com.dsmarket.modules.ai.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 问题池采集来源（ds_ai_issue_pool.source，String 落库，C3 §2 合法值）。
 *
 * <p>LOW_CONF = C2 低置信自动采集（search_knowledge NOT_COVERED → C1 SSE 低置信短路）；
 * DISLIKE = R2 点踩（feedback satisfied:false，凭 replyId 回查该轮原问句）。</p>
 */
@Getter
@AllArgsConstructor
public enum AiIssueSource {

    LOW_CONF("LOW_CONF", "低置信"),
    DISLIKE("DISLIKE", "点踩");

    public static final String LOW_CONF_VALUE = "LOW_CONF";
    public static final String DISLIKE_VALUE = "DISLIKE";

    private final String value;
    private final String displayName;

    /** @return 匹配项；无则 null（供 VO 组装与合法性校验，不抛） */
    public static AiIssueSource fromValueOrNull(String value) {
        if (value == null) {
            return null;
        }
        for (AiIssueSource s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        return null;
    }
}
