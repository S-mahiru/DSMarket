package com.dsmarket.modules.ai.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Set;

/**
 * 人工会话状态机（{@code ds_ai_support_session.status}，String 落库，C4 §5）。
 *
 * <p>流转：{@code pending_human → human_active}（坐席接入）/ {@code → message_left}（无人接+留言）/
 * {@code → closed}（坐席显式结束）；{@code message_left} 坐席回复<b>不自动关</b>（P2 决议）。
 * 终态 closed 不可再流转，closed 后新转人工 → 新建会话（H12/H19）。</p>
 *
 * <p><b>大小写铁律</b>（§3 枚举规范）：本枚举值为<b>小写蛇形</b>；{@code message.sender} 与
 * {@code origin} 才是大写常量。引用状态一律小写，禁止写 HUMAN_ACTIVE / active 之类变体。</p>
 *
 * <p><b>ai_active 故意不在本枚举内</b>：它是隐含态、本表不落库（AI 上下文在 Redis）。
 * 不提供该常量正是为了让人无从误写 —— 需要表达"纯 AI 态"时用"本表无行"（{@code exists=false}）。</p>
 */
@Getter
@AllArgsConstructor
public enum AiSupportSessionStatus {

    PENDING_HUMAN("pending_human", "等待接入"),
    HUMAN_ACTIVE("human_active", "人工处理中"),
    MESSAGE_LEFT("message_left", "已留言"),
    CLOSED("closed", "已结束");

    public static final String PENDING_HUMAN_VALUE = "pending_human";
    public static final String HUMAN_ACTIVE_VALUE = "human_active";
    public static final String MESSAGE_LEFT_VALUE = "message_left";
    public static final String CLOSED_VALUE = "closed";

    /**
     * 人工接管集合 PH（§4.4/§5 硬规则）：任一态收到买家消息一律走 support/message，不调 LLM。
     * 也是"同一买家至多一个活跃会话"的判定集合（与 V8 部分唯一索引 WHERE 子句一致）。
     */
    public static final Set<String> PH_VALUES = Set.of(PENDING_HUMAN_VALUE, HUMAN_ACTIVE_VALUE, MESSAGE_LEFT_VALUE);

    private final String value;
    private final String displayName;

    public static AiSupportSessionStatus fromValueOrNull(String value) {
        if (value == null) {
            return null;
        }
        for (AiSupportSessionStatus s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        return null;
    }

    /** 是否属于人工接管集合 PH（不调 LLM 的状态；closed 与隐含 ai_active 均不在内） */
    public static boolean isHumanTakeover(String value) {
        return value != null && PH_VALUES.contains(value);
    }
}
