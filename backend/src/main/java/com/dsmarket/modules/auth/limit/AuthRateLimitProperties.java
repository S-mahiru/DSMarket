package com.dsmarket.modules.auth.limit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录 / 注册限流参数（前缀 {@code security.auth-rate-limit}，审计 §2.4）。
 *
 * <p>口径全部集中在配置层，调整阈值只改 yml / 环境变量，不碰业务代码
 * （与 {@code AiProperties} 同一做法）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "security.auth-rate-limit")
public class AuthRateLimitProperties {

    /**
     * 总开关。
     *
     * <p><b>测试环境也不该置 false</b>：关掉等于让"限流写坏了"这类缺陷在其余 600+ 用例里
     * 完全不可见。测试 profile 的做法是保持开启、把阈值抬到不可能达到
     * （见 {@code application-test.yml}）。</p>
     */
    private boolean enabled = true;

    /** 同一 (账号, 来源IP) 对的失败次数达到此值即封锁 */
    private int accountLimit = 5;

    /** 同一来源 IP 的失败次数达到此值即封锁（拦密码喷洒：固定弱口令遍历用户名） */
    private int ipLimit = 20;

    /** 同一来源 IP 在 {@link #registerWindow} 内的注册次数上限 */
    private int registerLimit = 10;

    /**
     * 失败计数的记忆窗口。首次失败时置上，<b>后续失败不刷新</b> ——
     * 刷新会让"细水长流"式尝试永远清不掉计数，等效于把临时封锁变成永久封锁。
     */
    private Duration failureWindow = Duration.ofMinutes(15);

    /** 注册计数窗口 */
    private Duration registerWindow = Duration.ofHours(1);

    /** 首次达阈值时的封锁时长；此后每多失败一次翻倍（递增退避） */
    private long baseBlockSeconds = 60;

    /** 封锁时长上限，防无限翻倍 */
    private long maxBlockSeconds = 1800;
}
