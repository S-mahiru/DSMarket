package com.dsmarket.modules.ai.support;

import com.dsmarket.common.constant.RedisKeyConstant;
import com.dsmarket.modules.ai.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RedisSupportSeatPresence 单测（C4 §4.2 坐席在线键）。
 *
 * <p>重点锁两件事：①在线判定以<b>键是否存在</b>为权威，索引里的死成员要被清掉且不能导致误报在线；
 * ②Redis 故障时一律判"无人在线" —— 这是"AI 绝不谎报有真人"这条底线在基建层的落点，
 * 与限流器的 fail-open 方向相反。</p>
 */
class RedisSupportSeatPresenceTest {

    private static final long ADMIN = 42L;

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private SetOperations<String, String> setOps;
    private AiProperties properties;
    private RedisSupportSeatPresence presence;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        setOps = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);
        properties = new AiProperties();
        presence = new RedisSupportSeatPresence(redis, properties);
    }

    // ------------------------------------------------------------ anySeatOnline

    @Test
    void anySeatOnline_emptyIndex_returnsFalse() {
        when(setOps.members(RedisKeyConstant.AI_SEAT_INDEX)).thenReturn(Set.of());

        assertFalse(presence.anySeatOnline());
    }

    @Test
    void anySeatOnline_nullIndex_returnsFalse() {
        when(setOps.members(RedisKeyConstant.AI_SEAT_INDEX)).thenReturn(null);

        assertFalse(presence.anySeatOnline());
    }

    @Test
    void anySeatOnline_liveMember_returnsTrue() {
        when(setOps.members(RedisKeyConstant.AI_SEAT_INDEX)).thenReturn(Set.of(String.valueOf(ADMIN)));
        when(redis.hasKey(RedisKeyConstant.AI_SEAT + ADMIN)).thenReturn(true);

        assertTrue(presence.anySeatOnline());
    }

    @Test
    void anySeatOnline_staleMember_prunedAndNotCountedOnline() {
        // 索引里留着已 TTL 过期的成员：权威判定是"键在不在"，故必须判离线并顺手清理索引
        when(setOps.members(RedisKeyConstant.AI_SEAT_INDEX)).thenReturn(Set.of(String.valueOf(ADMIN)));
        when(redis.hasKey(RedisKeyConstant.AI_SEAT + ADMIN)).thenReturn(false);

        assertFalse(presence.anySeatOnline(), "索引成员过期 → 无人在线（不误报有人）");
        verify(setOps).remove(RedisKeyConstant.AI_SEAT_INDEX, String.valueOf(ADMIN));
    }

    @Test
    void anySeatOnline_redisDown_returnsFalse_failSafe() {
        // 与限流 fail-open 相反：判定不了就判离线 → 走诚实留言降级，绝不凭空说有客服
        when(setOps.members(anyString())).thenThrow(new RuntimeException("redis down"));

        assertFalse(presence.anySeatOnline());
    }

    // ------------------------------------------------------------ markOnline

    @Test
    void markOnline_firstHeartbeat_writesKeyWithTtl_andAddsIndex_returnsTrue() {
        when(redis.hasKey(RedisKeyConstant.AI_SEAT + ADMIN)).thenReturn(false);

        assertTrue(presence.markOnline(ADMIN), "首次心跳 = 由离线转在线，admin 流据此推 seat_status(login)");

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq(RedisKeyConstant.AI_SEAT + ADMIN),
                eq(RedisSupportSeatPresence.ONLINE_VALUE), ttl.capture());
        assertEquals(properties.getSupport().getSeatOfflineTtl(), ttl.getValue(),
                "TTL 必须是 45s 档（§4.2），写死别的值会让心跳间隔与判离线阈值脱钩");
        verify(setOps).add(RedisKeyConstant.AI_SEAT_INDEX, String.valueOf(ADMIN));
    }

    @Test
    void markOnline_repeatHeartbeat_returnsFalse() {
        // 多标签/持续心跳：键本来就在 → 静默续期，不再推一次 login（§4.2 P5 无顶号互斥）
        when(redis.hasKey(RedisKeyConstant.AI_SEAT + ADMIN)).thenReturn(true);

        assertFalse(presence.markOnline(ADMIN));
    }

    @Test
    void markOnline_redisDown_returnsFalse_neverClaimsOnline() {
        when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));

        assertFalse(presence.markOnline(ADMIN), "写不进在线键就不能声称上线");
    }

    // ------------------------------------------------------------ markOffline / isOnline

    @Test
    void markOffline_clearsKeyAndIndexMember() {
        presence.markOffline(ADMIN);

        verify(redis).delete(RedisKeyConstant.AI_SEAT + ADMIN);
        verify(setOps).remove(RedisKeyConstant.AI_SEAT_INDEX, String.valueOf(ADMIN));
    }

    @Test
    void markOffline_redisDown_swallowed() {
        // delete 返回 Boolean：用 doThrow 打桩会抛异常的方法
        doThrow(new RuntimeException("redis down")).when(redis).delete(anyString());

        presence.markOffline(ADMIN); // 不抛：键最终由 TTL 自然过期
    }

    @Test
    void isOnline_reflectsKeyExistence() {
        when(redis.hasKey(RedisKeyConstant.AI_SEAT + ADMIN)).thenReturn(true);
        assertTrue(presence.isOnline(ADMIN));

        when(redis.hasKey(RedisKeyConstant.AI_SEAT + ADMIN)).thenReturn(false);
        assertFalse(presence.isOnline(ADMIN));
    }

    @Test
    void isOnline_redisDown_returnsFalse() {
        when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));

        assertFalse(presence.isOnline(ADMIN));
    }

    @Test
    void anySeatOnline_doesNotUseKeysCommand() {
        // 防回归：全库 KEYS 扫描是 O(N) 阻塞命令，必须走成员索引
        when(setOps.members(anyString())).thenReturn(Set.of());

        presence.anySeatOnline();

        verify(redis, never()).keys(anyString());
    }

    @Test
    void markOnline_doesNotFire_onFailedWrite() {
        doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        assertFalse(presence.markOnline(ADMIN));
        verifyNoInteractions(setOps);
    }
}
