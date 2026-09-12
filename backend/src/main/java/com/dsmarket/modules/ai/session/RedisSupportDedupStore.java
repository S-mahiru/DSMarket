package com.dsmarket.modules.ai.session;

import cn.hutool.crypto.digest.DigestUtil;
import com.dsmarket.common.constant.RedisKeyConstant;
import com.dsmarket.modules.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 人工态 clientMsgId 幂等的 Redis 实现。
 * key = {@code dsm:ai:support:dedup:{userId}:{sessionId}:{sha256(clientMsgId)}}，
 * TTL 取 {@code ai.support.dedup-ttl}。
 *
 * <p>两处设计取舍：</p>
 * <ul>
 *   <li><b>clientMsgId 先 sha256 再拼 key</b>：它是任意用户输入，哈希后规避脏字符/超长污染（同
 *       {@link RedisChatDedupStore}）。</li>
 *   <li><b>sessionId 进 key</b>：离开 closed 后新建会话会拿到新 sessionId，同一条 clientMsgId
 *       不会误伤新会话的首次发送（H19 场景）。</li>
 * </ul>
 *
 * <p>Redis 不可用 → 查得 null / 写入失败仅告警：按"未重复"处理，宁可重一条也不阻断买家留言。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSupportDedupStore implements SupportDedupStore {

    private final StringRedisTemplate stringRedisTemplate;
    private final AiProperties properties;

    @Override
    public Long findMessageId(Long userId, Long sessionId, String clientMsgId) {
        try {
            String value = stringRedisTemplate.opsForValue().get(key(userId, sessionId, clientMsgId));
            return value == null ? null : Long.valueOf(value);
        } catch (Exception e) {
            log.warn("[ai][c4] 人工态幂等键读取失败，按未重复处理. userId={} err={}", userId, e.getMessage());
            return null;
        }
    }

    @Override
    public void mark(Long userId, Long sessionId, String clientMsgId, Long messageId) {
        try {
            stringRedisTemplate.opsForValue().set(key(userId, sessionId, clientMsgId),
                    String.valueOf(messageId), properties.getSupport().getDedupTtl());
        } catch (Exception e) {
            log.warn("[ai][c4] 人工态幂等键写入失败（不影响本次落库）. userId={} messageId={} err={}",
                    userId, messageId, e.getMessage());
        }
    }

    private String key(Long userId, Long sessionId, String clientMsgId) {
        return RedisKeyConstant.AI_SUPPORT_DEDUP + userId + ":" + sessionId + ":"
                + DigestUtil.sha256Hex(clientMsgId);
    }
}
