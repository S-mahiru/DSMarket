package com.dsmarket.modules.ai.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 知识条目类目（ds_ai_knowledge.category，String 落库，C2 §2 合法值）。
 */
@Getter
@AllArgsConstructor
public enum AiKnowledgeCategory {

    AFTER_SALE("after_sale", "售后"),
    SHIPPING("shipping", "物流"),
    PAYMENT("payment", "支付"),
    PRODUCT_POLICY("product_policy", "商品政策"),
    ACCOUNT("account", "账户"),
    OTHER("other", "其他");

    private final String value;
    private final String displayName;

    /** @return 匹配项；无则 null（供 VO 组装与合法性校验，不抛） */
    public static AiKnowledgeCategory fromValueOrNull(String value) {
        if (value == null) {
            return null;
        }
        for (AiKnowledgeCategory c : values()) {
            if (c.value.equals(value)) {
                return c;
            }
        }
        return null;
    }
}
