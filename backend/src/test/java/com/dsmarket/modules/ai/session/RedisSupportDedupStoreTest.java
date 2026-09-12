package com.dsmarket.modules.ai.session;

import cn.hutool.crypto.digest.DigestUtil;
import com.dsmarket.common.constant.RedisKeyConstant;
import com.dsmarket.modules.ai.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisSupportDedupStore 单测（C4 §4.4 P6 幂等键）。
 * 重点锁两条实现取舍：clientMsgId 必须 sha256 后入键（不落原文）、sessionId 必须进键
 * （closed 后新建会话不被旧 cid 误伤，H19）。
 */
class RedisSupportDedupStoreTest {

    private static final long USER = 500L;
    private static final long SESSION = 77L;

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private AiProperties properties;
    private RedisSupportDedupStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        properties = new AiProperties();
        store = new RedisSupportDedupStore(redis, properties);
    }

    @Test
    void mark_writesHashedKeyWithTtl() {
        store.mark(USER, SESSION, "cid-abc", 1234L);

        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(keyCap.capture(), eq("1234"), eq(properties.getSupport().getDedupTtl()));

        String key = keyCap.getValue();
        assertNotNull(key);
        assertFalse(key.contains("cid-abc"), "clientMsgId 是任意用户输入，必须哈希后入键（不落原文）");
        assertEquals(RedisKeyConstant.AI_SUPPORT_DEDUP + USER + ":" + SESSION + ":"
                + DigestUtil.sha256Hex("cid-abc"), key, "sessionId 必须进键：旧会话 cid 不误伤新会话（H19）");
    }

    @Test
    void find_returnPersistedMessageId() {
        when(valueOps.get(RedisKeyConstant.AI_SUPPORT_DEDUP + USER + ":" + SESSION + ":"
                + DigestUtil.sha256Hex("cid-abc"))).thenReturn("1234");

        assertEquals(1234L, store.findMessageId(USER, SESSION, "cid-abc"));
    }

    @Test
    void find_sameCidDifferentSession_notHit() {
        // H19：closed 后新建会话 → 新 sessionId → 同 cid 不应命中旧记录
        store.mark(USER, SESSION, "cid-reused", 1L);

        assertNull(store.findMessageId(USER, 88L, "cid-reused"), "不同会话的相同 cid 不应互判重复");
    }

    @Test
    void find_missing_returnsNull() {
        when(valueOps.get(anyString())).thenReturn(null);

        assertNull(store.findMessageId(USER, SESSION, "new-cid"));
    }

    @Test
    void find_redisFailure_returnsNull_treatedAsNotDuplicate() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));

        assertNull(store.findMessageId(USER, SESSION, "cid"), "幂等键不可用 → 按未重复处理，不阻断留言");
    }

    @Test
    void mark_redisFailure_swallowed() {
        // set 返回 void：只能用 doThrow 打桩（when(...) 对 void 方法不合法）
        doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        store.mark(USER, SESSION, "cid", 1L); // 不抛：本次已落库，登记失败不影响
    }
}
