package com.dsmarket.modules.ai.session;

import com.dsmarket.modules.ai.model.ChatRound;

import java.util.List;

/**
 * AI 会话轮条目存取（REQ C1-F1）。
 *
 * <p>接口化便于编排层单测注入内存替身；生产实现走 Redis（RedisAiSessionStore）。</p>
 */
public interface AiSessionStore {

    /**
     * 读取某用户最近会话轮条目（Redis 不可用/无历史 → 空列表，不抛错、不阻塞对话，REQ C1 E9）。
     */
    List<ChatRound> loadRounds(Long userId);

    /**
     * 追加一轮并回写（内部负责截断最近 N 轮 + 刷新 TTL）。Redis 不可用 → 记录日志降级（不阻塞对话）。
     */
    void appendRound(Long userId, ChatRound round);
}
