package com.dsmarket.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HexFormat;

/**
 * JWT 生成 / 解析 / 校验（HS256，无状态 Token）
 *
 * <p><b>口令版本声明 {@code pv}</b>（审计 12-readiness-audit §2.5）：token 里带一个由
 * 「当前口令哈希」派生出的摘要，过滤器拿它跟库里那条用户记录比对，改密后旧 token 立即失效。
 * 详见 {@link #passwordVersion(String)}。</p>
 */
@Component
public class JwtTokenProvider {

    /**
     * 口令版本声明名。过滤器读它、本类写它，<b>只有这一个字面量</b> —— 两边各写一份字符串
     * 迟早会分叉，而分叉的表现是「改密后旧 token 依然能用」这种静默失效。
     */
    public static final String CLAIM_PASSWORD_VERSION = "pv";

    private final SecretKey key;
    private final long expirationSeconds;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration}") long expirationSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = expirationSeconds;
    }

    /**
     * 由库里存的口令哈希派生「版本号」。
     *
     * <p><b>为什么用它当版本号，而不是新增 {@code token_version} 列</b>：不需要改表、不需要迁移
     * 文件（本项目迁移脚本有 glob 字节序的前科，能不动就不动）；且过滤器<b>本来就已经</b>为了
     * 「禁用即时生效」每个已认证请求 {@code selectById} 一次，{@code password} 列随之已经在手上，
     * 故这次比对是<b>零额外查询</b>。</p>
     *
     * <p><b>为什么安全</b>：BCrypt 每次编码都换随机盐，所以只要口令被重新设置过，哈希必然变化
     * （哪怕新旧口令字面相同）。摘要本身随 token 一起对客户端可见，但它是<b>不可逆的</b>：
     * 由它反推不出 BCrypt 哈希，更反推不出口令；而服务端校验时比的是库里的 BCrypt 哈希，
     * <b>拿到摘要并不能用来认证</b>。</p>
     *
     * @return 口令哈希的 SHA-256 十六进制串；哈希为空（异常数据）时返回 {@code null}，
     *         由调用方按「不可用」处理 —— 不要返回空串，否则一个伪造的 {@code pv=""} 会与之相等。
     */
    public static String passwordVersion(String passwordHash) {
        if (!StringUtils.hasText(passwordHash)) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(passwordHash.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // JDK 必须提供 SHA-256，走到这里说明运行环境坏了，不是可恢复的业务异常。
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public String generateToken(Long userId, String username, String role, String passwordHash) {
        Date now = new Date();
        String pv = passwordVersion(passwordHash);
        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationSeconds * 1000));
        if (pv != null) {
            // 口令哈希为空时不盖声明：不依赖 JJWT 对 null 的处理，且天然 fail-closed
            // —— 过滤器「缺声明即拒」，这种账号本就登不进来（matches() 过不了空哈希）。
            builder.claim(CLAIM_PASSWORD_VERSION, pv);
        }
        return builder.signWith(key).compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }
}
