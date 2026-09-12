package com.dsmarket.modules.ai.limit;

import com.dsmarket.common.constant.RedisKeyConstant;
import com.dsmarket.modules.ai.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisSupportRateLimiter 单测（C4 §4.4 人工态限流：10 次/分/用户）。
 * 覆盖额度内放行、超限拒绝、首计数置窗口过期、Redis 故障 fail-open。
 */
class RedisSupportRateLimiterTest {

    private static final long USER = 400L;

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private AiProperties properties;
    private RedisSupportRateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        properties = new AiProperties(); // 真配置：support.rateLimit=10 / rateWindow=1m
        limiter = new RedisSupportRateLimiter(redis, properties);
    }

    @Test
    void withinLimit_allows() {
        when(valueOps.increment(RedisKeyConstant.AI_SUPPORT_RATE_LIMIT + USER)).thenReturn(10L);

        assertTrue(limiter.allow(USER), "恰好到 10 次（上限）应放行");
    }

    @Test
    void overLimit_denies() {
        when(valueOps.increment(RedisKeyConstant.AI_SUPPORT_RATE_LIMIT + USER)).thenReturn(11L);

        assertFalse(limiter.allow(USER), "第 11 次应拒绝 → 服务层回 429");
    }

    @Test
    void firstIncrement_setsWindowExpire() {
        when(valueOps.increment(any())).thenReturn(1L);

        limiter.allow(USER);

        verify(redis).expire(eq(RedisKeyConstant.AI_SUPPORT_RATE_LIMIT + USER),
                eq(properties.getSupport().getRateWindow()));
    }

    @Test
    void subsequentIncrement_doesNotResetWindow() {
        when(valueOps.increment(any())).thenReturn(2L);

        limiter.allow(USER);

        verify(redis, never()).expire(any(), any(Duration.class));
    }

    @Test
    void redisFailure_failsOpen() {
        when(valueOps.increment(any())).thenThrow(new RuntimeException("redis down"));

        assertTrue(limiter.allow(USER), "Redis 不可用应放行：可用性优先于限流精度（对齐 C1 E9）");
    }
}
