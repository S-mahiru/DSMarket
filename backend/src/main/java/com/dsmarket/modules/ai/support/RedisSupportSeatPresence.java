package com.dsmarket.modules.ai.support;

import com.dsmarket.common.constant.RedisKeyConstant;
import com.dsmarket.modules.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 坐席存在性接缝的<b>生产实现</b>（C4 切片 2）：在线态存 Redis，由心跳驱动。
 *
 * <p>键 {@code dsm:ai:seat:{ADMIN_ID}} = {@value #ONLINE_VALUE}，TTL 取
 * {@code ai.support.seat-offline-ttl}（缺省 45s）。工作台每 {@code ai.support.heartbeat}（缺省 15s）
 * 心跳一次续期；TTL 自然过期即"客观离线"（REQ §4.2 P5 决议：离线判定靠 TTL 被动失效，
 * <b>不做主动扫描推送</b>）。</p>
 *
 * <h3>为什么还要一个成员索引</h3>
 * 回答"有没有人在线"需要知道有哪些 ADMIN_ID。直接 {@code KEYS dsm:ai:seat:*} 是 O(N) 全库扫描，
 * 在单线程 Redis 上会阻塞其它命令，随 key 总量增长而恶化。故心跳时顺带
 * {@code SADD dsm:ai:seat:index {adminId}}，判定时只遍历这一小撮成员。
 *
 * <p><b>索引不是权威</b>：成员可能已 TTL 过期，所以逐个 {@code hasKey} 复核，顺手 {@code SREM} 清理。
 * 失效方向是不对称的，且刻意选择安全的一侧 —— 索引多出死成员不会导致误报在线（有复核），
 * 索引漏成员只会让某个真在线的坐席暂时不被计入（每次心跳都会 SADD，不会持续漏）。
 * 换言之：<b>本实现的任何异常与不一致，都只会把结果推向"判无人在线"</b>，
 * 即推向"诚实留言"而非"谎报有人接"（REQ §1 底线）。</p>
 *
 * <p>Redis 不可用 → 一律返回 {@code false}（判离线）。这与限流器的 fail-open 相反是有意的：
 * 限流失效只是精度损失，而在线判定失效若 fail-open 就等于<b>凭空声称有客服在线</b>，
 * 是模块明令禁止的行为。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ai.support.seat-presence", havingValue = "redis", matchIfMissing = true)
public class RedisSupportSeatPresence implements SupportSeatPresence, SeatOnlineRegistry {

    static final String ONLINE_VALUE = "online";

    private final StringRedisTemplate stringRedisTemplate;
    private final AiProperties properties;

    // ------------------------------------------------------------ SupportSeatPresence（状态机窄接缝）

    @Override
    public boolean anySeatOnline() {
        try {
            Set<String> members = stringRedisTemplate.opsForSet().members(RedisKeyConstant.AI_SEAT_INDEX);
            if (members == null || members.isEmpty()) {
                return false;
            }
            for (String adminId : members) {
                if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKeyConstant.AI_SEAT + adminId))) {
                    return true;
                }
                // 索引里的陈旧成员（键已 TTL 过期）顺手清掉，避免索引无界增长
                stringRedisTemplate.opsForSet().remove(RedisKeyConstant.AI_SEAT_INDEX, adminId);
            }
            return false;
        } catch (Exception e) {
            // 失败方向：判"无人在线" → 买家得到诚实留言引导，而不是被告知有客服其实没人
            log.warn("[ai][c4] 坐席在线查询失败，按无人在线处理（走诚实留言降级）. err={}", e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------ SeatOnlineRegistry（工作台写操作）

    @Override
    public boolean markOnline(Long adminId) {
        try {
            String key = RedisKeyConstant.AI_SEAT + adminId;
            boolean wasOnline = Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
            stringRedisTemplate.opsForValue().set(key, ONLINE_VALUE,
                    properties.getSupport().getSeatOfflineTtl());
            stringRedisTemplate.opsForSet().add(RedisKeyConstant.AI_SEAT_INDEX, String.valueOf(adminId));
            return !wasOnline;
        } catch (Exception e) {
            // 心跳写失败 = 无法声称在线；返回 false（不当成"新上线"），下一次心跳自然重试
            log.warn("[ai][c4] 坐席心跳写入失败. adminId={} err={}", adminId, e.getMessage());
            return false;
        }
    }

    @Override
    public void markOffline(Long adminId) {
        try {
            stringRedisTemplate.delete(RedisKeyConstant.AI_SEAT + adminId);
            stringRedisTemplate.opsForSet().remove(RedisKeyConstant.AI_SEAT_INDEX, String.valueOf(adminId));
        } catch (Exception e) {
            log.warn("[ai][c4] 坐席显式登出清理失败（键将由 TTL 自然过期）. adminId={} err={}",
                    adminId, e.getMessage());
        }
    }

    @Override
    public boolean isOnline(Long adminId) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKeyConstant.AI_SEAT + adminId));
        } catch (Exception e) {
            log.warn("[ai][c4] 坐席在线查询失败，按离线处理. adminId={} err={}", adminId, e.getMessage());
            return false;
        }
    }
}
