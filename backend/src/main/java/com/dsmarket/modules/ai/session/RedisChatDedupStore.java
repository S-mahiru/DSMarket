package com.dsmarket.modules.ai.session;

import cn.hutool.crypto.digest.DigestUtil;
import com.dsmarket.common.constant.RedisKeyConstant;
import com.dsmarket.modules.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis clientMsgId 幂等存储。key = {@code dsm:ai:chat:dedup:{userId}:{sha256(clientMsgId)}}
 * （clientMsgId 任意用户输入，哈希后再拼 key，避免脏字符/超长污染），TTL 取 {@code ai.chat.dedup-ttl}。
 * Redis 不可用 → 查得 null（当次照常生成，不做回放）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatDedupStore implements ChatDedupStore {

    private final StringRedisTemplate stringRedisTemplate;
    private final AiProperties properties;

    @Override
    public String findReplyId(Long userId, String clientMsgId) {
        try {
            return stringRedisTemplate.opsForValue().get(key(userId, clientMsgId));
        } catch (Exception e) {
            log.warn("[ai] 幂等键读取失败，按未重复处理. userId={} err={}", userId, e.getMessage());
            return null;
        }
    }

    @Override
    public void mark(Long userId, String clientMsgId, String replyId) {
        try {
            stringRedisTemplate.opsForValue()
                    .set(key(userId, clientMsgId), replyId, properties.getChat().getDedupTtl());
        } catch (Exception e) {
            log.warn("[ai] 幂等键写入失败（不影响本轮）. userId={} replyId={} err={}",
                    userId, replyId, e.getMessage());
        }
    }

    private String key(Long userId, String clientMsgId) {
        return RedisKeyConstant.AI_CHAT_DEDUP + userId + ":" + DigestUtil.sha256Hex(clientMsgId);
    }
}
