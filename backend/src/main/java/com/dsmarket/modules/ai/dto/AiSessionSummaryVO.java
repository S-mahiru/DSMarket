package com.dsmarket.modules.ai.dto;

import lombok.Data;

/**
 * 工作台会话详情里的 AI 对话回放条目（C4 §4.5 L 决议）。
 *
 * <p><b>原文直读，不经任何模型</b>：从 Redis {@code ai:session:{userId}} 取最近 N 轮
 * （{@code ai.support.ai-summary-rounds}，缺省 5）的"用户问 / AI 答"原文，
 * 不生成摘要、不额外调用 LLM、不产生成本。Redis 会话过期或从未有过 AI 对话 → 列表为空，
 * 前端显示"无 AI 对话记录（转人工前无近期 AI 问答）"。</p>
 *
 * <p>取不到就说取不到 —— 不引入 AI 会话的 PG 留痕来"补历史"（v3 范围外）。</p>
 */
@Data
public class AiSessionSummaryVO {

    /** 用户当时的提问原文 */
    private String userContent;

    /** AI 当时的回答原文 */
    private String assistantContent;

    /** 该轮写入时间（epoch millis，来源 {@code ChatRound.ts}） */
    private long ts;
}
