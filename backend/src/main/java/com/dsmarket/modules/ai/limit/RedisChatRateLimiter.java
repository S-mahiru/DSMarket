package com.dsmarket.modules.ai.limit;

import com.dsmarket.common.constant.RedisKeyConstant;
import com.dsmarket.modules.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 固定窗口限流（REQ C1 E2/§9）。{@code dsm:ai:chat:limit:{userId}} INCR，
 * 首次置窗口过期；计数 ≤ limit 放行。Redis 不可用 → 放行（fail-open，与 E9 降级哲学一致）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatRateLimiter implements ChatRateLimiter {

    private final StringRedisTemplate stringRedisTemplate;
    private final AiProperties properties;

    @Override
    public boolean allow(Long userId) {
        String key = RedisKeyConstant.AI_CHAT_LIMIT + userId;
        try {
            Long count = stringRedisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                Duration window = properties.getChat().getRateWindow();
                stringRedisTemplate.expire(key, window);
            }
            return count == null || count <= properties.getChat().getRateLimit();
        } catch (Exception e) {
            log.warn("[ai] 限流计数不可用，放行. userId={} err={}", userId, e.getMessage());
            return true;
        }
    }
}
