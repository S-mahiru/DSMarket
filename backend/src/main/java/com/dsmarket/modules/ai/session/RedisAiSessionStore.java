package com.dsmarket.modules.ai.session;

import com.dsmarket.common.constant.RedisKeyConstant;
import com.dsmarket.modules.ai.config.AiProperties;
import com.dsmarket.modules.ai.model.ChatRound;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 会话轮条目的 Redis 实现（REQ C1-F1 / C1 §4.1）。
 *
 * <p>Key = {@code dsm:ai:session:{userId}}（每用户单键，AI 态无独立 sessionId，P7 决议），
 * Value = ChatRound 数组的 JSON 字符串（StringRedisTemplate 原样落盘，跨语言可读）。
 * TTL/最近轮数取 {@code ai.session.*}。Redis 不可用一律降级：读→空、写→丢一轮并记日志，
 * 不阻塞对话（REQ C1 E9）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisAiSessionStore implements AiSessionStore {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final AiProperties properties;

    @Override
    public List<ChatRound> loadRounds(Long userId) {
        try {
            String raw = stringRedisTemplate.opsForValue().get(key(userId));
            if (raw == null || raw.isBlank()) {
                return new ArrayList<>();
            }
            return RoundWindow.trim(
                    objectMapper.readValue(raw, new TypeReference<List<ChatRound>>() {
                    }),
                    properties.getSession().getMaxRounds());
        } catch (Exception e) {
            // E9：Redis 读写失败 → 无历史继续，不阻塞对话
            log.warn("[ai] 会话读取失败，降级为无历史. userId={} err={}", userId, e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void appendRound(Long userId, ChatRound round) {
        try {
            List<ChatRound> rounds = new ArrayList<>(loadRounds(userId));
            rounds.add(round);
            List<ChatRound> trimmed = RoundWindow.trim(rounds, properties.getSession().getMaxRounds());
            String json = objectMapper.writeValueAsString(trimmed);
            stringRedisTemplate.opsForValue()
                    .set(key(userId), json, properties.getSession().getTtl());
        } catch (Exception e) {
            log.warn("[ai] 会话写回失败，本轮回执未入库. userId={} replyId={} err={}",
                    userId, round.getReplyId(), e.getMessage());
        }
    }

    private String key(Long userId) {
        return RedisKeyConstant.AI_SESSION + userId;
    }
}
