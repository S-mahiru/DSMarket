package com.dsmarket.modules.ai.limit;

import com.dsmarket.common.constant.RedisKeyConstant;
import com.dsmarket.modules.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 人工态限流的 Redis 固定窗口实现（REQ C4 §4.4）。{@code dsm:ai:support:rl:{userId}} INCR，
 * 首次置窗口过期；计数 ≤ {@code ai.support.rate-limit} 放行。
 *
 * <p>与 {@link RedisChatRateLimiter} 同构（含 fail-open）：Redis 不可用时放行 —— 聊天链路的可用性
 * 优先于限流精度，与 C1 E9 降级哲学一致。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSupportRateLimiter implements SupportRateLimiter {

    private final StringRedisTemplate stringRedisTemplate;
    private final AiProperties properties;

    @Override
    public boolean allow(Long userId) {
        String key = RedisKeyConstant.AI_SUPPORT_RATE_LIMIT + userId;
        try {
            Long count = stringRedisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                Duration window = properties.getSupport().getRateWindow();
                stringRedisTemplate.expire(key, window);
            }
            return count == null || count <= properties.getSupport().getRateLimit();
        } catch (Exception e) {
            log.warn("[ai][c4] 人工态限流计数不可用，放行. userId={} err={}", userId, e.getMessage());
            return true;
        }
    }
}
