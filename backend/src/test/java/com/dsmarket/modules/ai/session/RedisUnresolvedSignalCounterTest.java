package com.dsmarket.modules.ai.session;

import com.dsmarket.common.constant.RedisKeyConstant;
import com.dsmarket.modules.ai.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisUnresolvedSignalCounter 单测（C4 §4.6 F6 触发③ 的计数底座）。
 *
 * <p>重点锁三条实现取舍：① key 形如 {@code dsm:ai:unresolved:{userId}}；② TTL 取
 * {@code ai.session.ttl} 且<b>每次自增都刷新</b>（滚动窗口，固定窗口会让 29 分钟时的第二个信号
 * 撞上第一个信号的过期点）；③ 全方法 fail-open —— Redis 挂了不能让对话跟着挂。</p>
 */
class RedisUnresolvedSignalCounterTest {

    private static final long USER = 500L;

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private AiProperties properties;
    private RedisUnresolvedSignalCounter counter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        properties = new AiProperties();
        counter = new RedisUnresolvedSignalCounter(redis, properties);
    }

    @Test
    void increment_keyShapeAndRollingTtl() {
        when(valueOps.increment(anyString())).thenReturn(1L);

        assertEquals(1, counter.increment(USER));

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(valueOps).increment(keyCap.capture());
        assertEquals(RedisKeyConstant.AI_UNRESOLVED + USER, keyCap.getValue(),
                "key = dsm:ai:unresolved:{userId}（按用户单键，跨会话由 TTL 兜）");
        verify(redis).expire(eq(RedisKeyConstant.AI_UNRESOLVED + USER),
                eq(properties.getSession().getTtl()));
    }

    @Test
    void increment_refreshesTtlEveryTime_notOnlyOnFirst() {
        // 滚动窗口语义：第二次自增也必须刷新 TTL。
        // 只在 count==1 时 EXPIRE 是**固定窗口**（限流器该那样，这里不该）：
        // 买家 29 分钟时的第二个信号会正好撞上第一个信号设下的过期点，读出来还是 1，白攒。
        when(valueOps.increment(anyString())).thenReturn(2L);

        assertEquals(2, counter.increment(USER));

        verify(redis).expire(anyString(), any(Duration.class));
    }

    @Test
    void count_readsPersistedValue() {
        when(valueOps.get(RedisKeyConstant.AI_UNRESOLVED + USER)).thenReturn("3");
        assertEquals(3, counter.count(USER));
    }

    @Test
    void count_missingKey_isZero() {
        when(valueOps.get(anyString())).thenReturn(null);
        assertEquals(0, counter.count(USER), "没有键 = 没攒过信号");
    }

    @Test
    void count_redisFailure_isZero_notThrow() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));
        assertEquals(0, counter.count(USER), "计数读不到 → 至多少发一次气泡，绝不阻断对话");
    }

    @Test
    void count_corruptedValue_isZero() {
        // 手工改过 Redis / 别的写入方污染 → parseInt 抛异常，仍按 0 处理
        when(valueOps.get(anyString())).thenReturn("not-a-number");
        assertEquals(0, counter.count(USER));
    }

    @Test
    void increment_redisFailure_isNegativeOne_notThrow() {
        when(valueOps.increment(anyString())).thenThrow(new RuntimeException("redis down"));
        assertEquals(-1, counter.increment(USER), "自增失败返回 -1，调用方不该拿它做判定");
    }

    @Test
    void clear_deletesKey() {
        counter.clear(USER);
        verify(redis).delete(RedisKeyConstant.AI_UNRESOLVED + USER);
    }

    @Test
    void clear_redisFailure_swallowed() {
        // 清零失败不影响转人工本身（转人工是 PG 里的状态机，不依赖 Redis）
        when(redis.delete(anyString())).thenThrow(new RuntimeException("redis down"));
        counter.clear(USER); // 不抛
    }

    @Test
    void nullUserId_isNoop() {
        assertEquals(-1, counter.increment(null));
        assertEquals(0, counter.count(null));
        counter.clear(null);
        verify(valueOps, never()).increment(anyString());
        verify(redis, never()).delete(anyString());
    }
}
