package com.dsmarket.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 客户端 IP 解析 —— 限流的<b>信任边界</b>（审计 §2.4）。
 *
 * <p><b>为什么必须是个显式开关，而不是"有头就信"</b>：{@code X-Real-IP} 与
 * {@code X-Forwarded-For} 都是普通请求头，任何能直连后端的人都能自带。若后端监听
 * {@code 0.0.0.0:8080} 而又去读这些头，攻击者每换一个假 IP 就能让限流计数落进不同的桶 ——
 * <b>限流看着生效，实则形同虚设</b>。这正是本项目反复强调的那类"假绿"，
 * 故此处把信任边界做成配置项，并要求它与 {@code server.address} <b>成对</b>设置。</p>
 *
 * <p>两种取值的语义：</p>
 * <ul>
 *   <li>{@code false}（默认，dev / test 用）：取 {@link HttpServletRequest#getRemoteAddr()}，
 *       即 TCP 对端地址，客户端伪造不了。代价是经过反向代理后拿到的是<b>反代自己</b>的地址 ——
 *       但这只是让 IP 维度退化，不会造出"看着已限流"的假象。</li>
 *   <li>{@code true}（仅 prod，且必须配合 {@code server.address=127.0.0.1}）：取 {@code X-Real-IP}。
 *       它由 {@code deploy/nginx/nginx.conf} 以 {@code proxy_set_header X-Real-IP $remote_addr}
 *       <b>覆盖写</b>，客户端自带的同名头会被盖掉，因而可信 —— 前提是后端只有反代一条路可进。</li>
 * </ul>
 *
 * <p><b>刻意不回落到 {@code X-Forwarded-For}</b>：那是<b>追加</b>语义
 * （{@code $proxy_add_x_forwarded_for}），客户端可以先塞一段伪造链，且"该取第几段"本身
 * 就是个容易定错的约定。缺 {@code X-Real-IP} 时宁可用 {@code remoteAddr}
 * （多半就是反代自己，最坏也只是计数落进同一个桶），也不去解析一条可被前置伪造的链。</p>
 */
@Slf4j
@Component
public class ClientIpResolver {

    private final boolean trustProxyHeader;
    private final String headerName;

    public ClientIpResolver(
            @Value("${security.client-ip.trust-proxy-header:false}") boolean trustProxyHeader,
            @Value("${security.client-ip.header:X-Real-IP}") String headerName) {
        this.trustProxyHeader = trustProxyHeader;
        this.headerName = headerName;
        if (trustProxyHeader) {
            // 这个假设是整条限流链的承重点，写在启动日志里才可被审计：
            // 光靠 yml 注释，没人会在改了 server.address 之后回来读它。
            log.warn("[auth] 已启用代理头信任，客户端 IP 取自 {}。此配置仅在【后端不可能被绕过反向代理"
                    + "直连】时安全 —— 若 server.address 被改成非回环地址（或后端直接暴露到公网），"
                    + "该头即可被任意伪造，基于 IP 的限流会退化成一个摆设。", headerName);
        }
    }

    /**
     * 解析客户端 IP。
     *
     * <p>永不返回 null / 空串：限流键会以它作后缀，空值会让所有请求挤进同一个桶，
     * 反而制造出"人人都被限流"的假象。取不到时统一返回 {@code "unknown"}。</p>
     */
    public String resolve(HttpServletRequest request) {
        if (trustProxyHeader) {
            String fromHeader = request.getHeader(headerName);
            if (StringUtils.hasText(fromHeader)) {
                return fromHeader.trim();
            }
        }
        String remote = request.getRemoteAddr();
        return StringUtils.hasText(remote) ? remote : "unknown";
    }
}
