package com.dsmarket.modules.ai.session;

import com.dsmarket.common.constant.RedisKeyConstant;
import com.dsmarket.modules.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 未解决信号计数：key = {@code dsm:ai:unresolved:{userId}}，TTL 取 {@code ai.session.ttl}
 * （与 AI 会话同寿）。
 *
 * <p><b>TTL 语义</b>：每次 {@link #increment} <b>滚动刷新</b>，与 {@code RedisAiSessionStore}
 * 的轮条目同款语义。两个未解决信号之间若隔了超过一个 TTL，前一个已过期 —— 这是<b>有意</b>的：
 * "未解决"要发生在<b>当下这个会话上下文</b>里，隔了半小时的另一轮不该被算作同一批。
 * （严格说计数键可能比会话键晚一点过期，最多晚一个 TTL；实际影响为零 —— 真有 30 分钟静默，
 * 买家早就走了。这里写出来是为了不留"我以为它严格同步"的暗坑。）</p>
 *
 * <p>全方法 fail-open（对齐 {@code RedisChatDedupStore} / {@code RedisSupportRateLimiter}）：
 * 计数器只是"要不要多发一个提示气泡"，任何异常都不该影响对话。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisUnresolvedSignalCounter implements UnresolvedSignalCounter {

    private final StringRedisTemplate stringRedisTemplate;
    private final AiProperties properties;

    @Override
    public int increment(Long userId) {
        if (userId == null) {
            return -1;
        }
        try {
            Long n = stringRedisTemplate.opsForValue().increment(key(userId));
            // 每次自增都刷新 TTL（滚动窗口）。**不能**只在 count==1 时设 EXPIRE ——
            // 那是固定窗口语义（对限流器正确，对本计数错误）：买家 29 分钟时的第二个信号
            // 会撞上第一个信号设下的过期点，读出来还是 1。
            // 代价：INCR 与 EXPIRE 之间进程被杀会留下一个不过期的键。后果有限（单用户单键，
            // 转人工时会 clear），故不为它引 Lua 脚本。
            stringRedisTemplate.expire(key(userId), ttl());
            return n == null ? -1 : n.intValue();
        } catch (Exception e) {
            log.warn("[ai][c4] 未解决信号自增失败（不影响对话）. userId={} err={}", userId, e.getMessage());
            return -1;
        }
    }

    @Override
    public int count(Long userId) {
        if (userId == null) {
            return 0;
        }
        try {
            String v = stringRedisTemplate.opsForValue().get(key(userId));
            return v == null ? 0 : Integer.parseInt(v);
        } catch (Exception e) {
            // 读不到 = 当 0：少发一次气泡，绝不因为计数失败把对话打挂
            log.warn("[ai][c4] 未解决信号读取失败，按 0 处理. userId={} err={}", userId, e.getMessage());
            return 0;
        }
    }

    @Override
    public void clear(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            stringRedisTemplate.delete(key(userId));
        } catch (Exception e) {
            log.warn("[ai][c4] 未解决信号清零失败（不影响转人工）. userId={} err={}", userId, e.getMessage());
        }
    }

    private String key(Long userId) {
        return RedisKeyConstant.AI_UNRESOLVED + userId;
    }

    private Duration ttl() {
        return properties.getSession().getTtl();
    }
}
