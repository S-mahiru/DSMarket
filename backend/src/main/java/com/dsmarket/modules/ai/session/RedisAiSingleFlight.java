package com.dsmarket.modules.ai.session;

import com.dsmarket.common.constant.RedisKeyConstant;
import com.dsmarket.modules.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * AI 单飞行的 Redis 实现（REQ C1 E12）。
 *
 * <p>Key = {@code dsm:ai:inflight:{userId}}，{@code setIfAbsent} 原子占位，仅一个线程能成功。
 * 正常路径由编排层 finally 释放；异常崩溃靠 {@code ai.inflight.ttl} 兜底自动过期。</p>
 *
 * <p>Redis 不可用 → 放行（fail-open）：宁可牺牲并发去重也不阻塞对话（与 E9 降级哲学一致）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisAiSingleFlight implements AiSingleFlight {

    private final StringRedisTemplate stringRedisTemplate;
    private final AiProperties properties;

    @Override
    public boolean tryAcquire(Long userId) {
        try {
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key(userId), "1", properties.getInflight().getTtl());
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            log.warn("[ai] 单飞行锁不可用，放行. userId={} err={}", userId, e.getMessage());
            return true;
        }
    }

    @Override
    public void release(Long userId) {
        try {
            stringRedisTemplate.delete(key(userId));
        } catch (Exception e) {
            log.warn("[ai] 单飞行锁释放失败（将由 TTL 兜底）. userId={} err={}", userId, e.getMessage());
        }
    }

    private String key(Long userId) {
        return RedisKeyConstant.AI_INFLIGHT + userId;
    }
}
