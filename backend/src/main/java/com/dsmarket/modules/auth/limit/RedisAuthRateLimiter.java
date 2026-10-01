package com.dsmarket.modules.auth.limit;

import com.dsmarket.common.constant.RedisKeyConstant;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的登录 / 注册限流（审计 §2.4）。
 *
 * <p><b>键的构成</b>（见 {@link RedisKeyConstant#RATE_LIMIT_LOGIN}）：
 * 分 {@code acct} 与 {@code ip} 两个维度，各自有一份"失败计数"和一份"封锁标记"。
 * 封锁标记只判存在与否，<b>它的 TTL 就是剩余封锁时长</b> —— 于是"解除封锁"不需要任何
 * 定时任务或清理逻辑，Redis 的键过期本身就是解除。</p>
 *
 * <p><b>Redis 不可用时一律放行（fail-open）</b>，与 AI 限流
 * （{@code RedisChatRateLimiter}）的 E9 降级哲学一致：限流是加固，不是可用性的前提，
 * 不该因为缓存抖动把所有人挡在门外。代价要如实说明 —— <b>Redis 挂掉的这段时间里登录是不限流的</b>，
 * 所以这只是可用性优先的取舍，不是"限流依然生效"。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisAuthRateLimiter implements AuthRateLimiter {

    /** 失败计数 / 封锁标记的键内作用域前缀 */
    private static final String SCOPE_ACCOUNT = "acct:";
    private static final String SCOPE_IP = "ip:";
    private static final String KIND_FAILURE = "f:";
    private static final String KIND_BLOCK = "b:";
    /** 递增退避的最大移位步数，纯防 {@code <<} 溢出 */
    private static final int MAX_BACKOFF_STEP = 20;

    private final StringRedisTemplate redisTemplate;
    private final AuthRateLimitProperties properties;

    @Override
    public void checkLogin(String username, String clientIp) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            long waitMillis = Math.max(
                    remainingBlockMillis(SCOPE_ACCOUNT + accountHash(username) + ":" + clientIp),
                    remainingBlockMillis(SCOPE_IP + clientIp));
            if (waitMillis > 0) {
                throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS.getCode(),
                        "登录失败次数过多，请 " + toDisplaySeconds(waitMillis) + " 秒后再试");
            }
        } catch (BusinessException e) {
            // 业务拒绝要穿透出去，不能被下面的兜底 catch 吞掉变成放行
            throw e;
        } catch (Exception e) {
            log.warn("[auth] 登录限流检查不可用，本次按放行处理：{}", e.getMessage());
        }
    }

    @Override
    public void onLoginFailure(String username, String clientIp) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            recordFailure(SCOPE_ACCOUNT + accountHash(username) + ":" + clientIp, properties.getAccountLimit());
            recordFailure(SCOPE_IP + clientIp, properties.getIpLimit());
        } catch (Exception e) {
            log.warn("[auth] 登录失败计数不可用：{}", e.getMessage());
        }
    }

    @Override
    public void onLoginSuccess(String username, String clientIp) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            // 只清"账号×IP"这一格的失败计数 —— 让输错几次口令的本人马上恢复正常。
            // 【刻意不清 IP 维度】：那是密码喷洒的探测器，一个有效口令不该把它整个抹掉，
            // 否则攻击者只要手里有一个能登进去的账号，就能反复重置自己 IP 的失败计数。
            redisTemplate.delete(failureKey(SCOPE_ACCOUNT + accountHash(username) + ":" + clientIp));
        } catch (Exception e) {
            log.warn("[auth] 登录失败计数清理不可用：{}", e.getMessage());
        }
    }

    @Override
    public void checkRegister(String clientIp) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            String key = RedisKeyConstant.RATE_LIMIT_REGISTER + SCOPE_IP + clientIp;
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, properties.getRegisterWindow());
            }
            if (count != null && count > properties.getRegisterLimit()) {
                throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS.getCode(),
                        "注册过于频繁，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[auth] 注册限流不可用，本次按放行处理：{}", e.getMessage());
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 剩余封锁<b>毫秒</b>数；未封锁返回 0 或负数（Redis 对不存在的键返回 -2）。
     *
     * <p><b>必须用毫秒，不能用秒</b> —— 这是实测踩出来的：{@code getExpire(key, SECONDS)}
     * 走 PTTL 再<b>向下取整</b>，一个还剩 997 毫秒的封锁键读出来是 {@code 0}，
     * 于是 {@code wait > 0} 这个闸门恒不成立，限流<b>整段失效</b>。</p>
     *
     * <p>阴险之处在于它平时看不出来：默认 {@code base-block-seconds=60} 时读成 59，
     * 一切正常；只有把阈值调到 1 秒（本项目的集成测试就是这么做的）才会整段露馅。
     * 而即便在默认配置下它也是真缺陷 —— 封锁只剩不到 1 秒时读作 0，
     * 攻击者每次都能从窗口末尾那几百毫秒溜进来。顺带一提，
     * 同为"读 TTL"的 {@code getExpire(key)}（不带单位）走的是 Redis 的 TTL 命令、
     * 会四舍五入，与带单位的那个重载<b>不是一个语义</b>，别互相当替身用。</p>
     */
    private long remainingBlockMillis(String scopeAndId) {
        Long ttl = redisTemplate.getExpire(blockKey(scopeAndId), TimeUnit.MILLISECONDS);
        return ttl == null ? 0L : ttl;
    }

    /** 毫秒 → 展示用秒数，向上取整：剩 100 毫秒该说"请 1 秒后再试"，不能说"请 0 秒"。 */
    private static long toDisplaySeconds(long millis) {
        return (millis + 999) / 1000;
    }

    /**
     * 记一次失败，达阈值即写封锁标记。
     *
     * <p>写封锁用的是 {@code SET}（而非 {@code SETNX}）：每次新的失败都重写一遍，
     * 于是"继续尝试"会把封锁窗口从<b>当下</b>重新起算，而失败计数只增不减 ⇒
     * 下一次的窗口又比上一次长。递增退避正是这两件事叠加出来的。</p>
     */
    private void recordFailure(String scopeAndId, int limit) {
        String failKey = failureKey(scopeAndId);
        Long count = redisTemplate.opsForValue().increment(failKey);
        if (count == null) {
            return;
        }
        if (count == 1L) {
            redisTemplate.expire(failKey, properties.getFailureWindow());
        }
        if (count >= limit) {
            long blockSeconds = blockSeconds(count, limit);
            redisTemplate.opsForValue().set(blockKey(scopeAndId), "1", Duration.ofSeconds(blockSeconds));
        }
    }

    /**
     * 递增退避曲线：第 {@code limit} 次失败封 {@code base} 秒，此后每多失败一次翻倍，封顶 {@code max}。
     *
     * <p>取最小 {@code base} 而不为 0，是为了让"刚好达阈值"也是一个真实的等待，
     * 而不是一个可以立刻重试的空封锁。</p>
     */
    long blockSeconds(long failures, int limit) {
        long step = Math.min(failures - limit, MAX_BACKOFF_STEP);
        // 下界取 1：base=0 会算出 Duration.ZERO，而 Spring Data 把零/负 Duration 当成
        // 【不过期】下发布（键永久留存），封锁标记于是变成"存在但 TTL=-1"，
        // remainingBlockMillis 读到 -1 判为未封锁 —— 配错一个数就让整条限流静默失效。
        long base = Math.max(1, properties.getBaseBlockSeconds());
        long seconds = base << step;
        return Math.min(seconds, Math.max(1, properties.getMaxBlockSeconds()));
    }

    private String failureKey(String scopeAndId) {
        return RedisKeyConstant.RATE_LIMIT_LOGIN + KIND_FAILURE + scopeAndId;
    }

    private String blockKey(String scopeAndId) {
        return RedisKeyConstant.RATE_LIMIT_LOGIN + KIND_BLOCK + scopeAndId;
    }

    /**
     * 账号 → 定长十六进制摘要。
     *
     * <p>不是为了保密（Redis 里本来就有 AI 会话原文，它不是隐私边界），而是为了<b>键不歧义</b>：
     * {@code LoginRequest.username} 只校验非空，可以带 {@code :}；而 IPv6 地址本身也带 {@code :}。
     * 直接拼接会让 {@code ("a:b", "c")} 与 {@code ("a", "b:c")} 落到同一个键上。</p>
     */
    private static String accountHash(String username) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(username.getBytes(StandardCharsets.UTF_8));
            // 128 位足够（碰撞概率可忽略），只取前 32 个十六进制字符让键短一些
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
