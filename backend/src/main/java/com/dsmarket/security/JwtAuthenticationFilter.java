package com.dsmarket.security;

import com.dsmarket.common.constant.RedisKeyConstant;
import com.dsmarket.common.enums.UserStatusEnum;
import com.dsmarket.modules.user.entity.User;
import com.dsmarket.modules.user.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器：提取 Bearer Token → 校验（Redis 黑名单 + 用户状态 + 口令版本）→ 注入 SecurityContext
 *
 * <p><b>为什么验签之后还要查一次库</b>（审计 12-readiness-audit §1.1 / §2.5）：
 * JWT 是自包含的，签发之后服务端对它没有任何控制力。只验签的话，「管理员禁用账号」在既有 token
 * 的剩余有效期（{@code jwt.expiration}，默认 24h）内<b>完全不生效</b> —— 登录接口拦得住新登录，
 * 拦不住手里已经拿着 token 的人。故此处补一次状态校验，让禁用<b>立即</b>生效。</p>
 *
 * <p><b>同一次查库顺带做了口令版本校验</b>（审计 §2.5 的另一半）：账号被禁用和口令被改是两件事，
 * 前者靠 {@code status}，后者靠 {@code pv} 声明与当前口令哈希摘要比对。两者共用上面那次
 * {@code selectById}，故口令这一半是<b>零额外查询</b>的。派生摘要的方法只有一处
 * （{@link JwtTokenProvider#passwordVersion(String)}），两边不各写一份。</p>
 *
 * <p><b>角色仍取自 token，不取自这次查库的结果</b>：这是既有的已拍板行为
 * （REQ-20260912 §12.2 的 D7，「JWT {@code role} 为签发快照，改角色需重新登录」）。
 * 改成读库的 role 会让改角色立刻生效 —— 那是另一项决定，别在这里顺手改。</p>
 *
 * <p><b>代价</b>：每个已认证请求多一次主键查询（{@code selectById}，经 {@code @TableLogic} 自动带
 * {@code deleted = 0}）。<b>不为此加缓存</b> —— 带 TTL 的缓存会把「禁用立即生效」退化成
 * 「最多 N 秒后生效」，而那正是本类要修的东西。真到需要压这一下的量级，再考虑带主动失效的缓存。</p>
 *
 * <p><b>异常分两类记，不要合并</b>：token 过期/签名错是<b>日常</b>（客户端拿旧 token 重试），
 * 记日志等于刷屏；而 Redis / DB 不可用是<b>故障</b>，必须留痕 —— 本项目实测过
 * 「Redis 口令配错 → 应用照常启动、全部日志仅一行、所有已登录请求静默 401」，
 * 那种静默正是空 {@code catch} 造出来的。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final RedisTemplate<String, Object> redisTemplate;
    private final UserMapper userMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (StringUtils.hasText(header) && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length());
            try {
                Boolean blacklisted = redisTemplate.hasKey(RedisKeyConstant.TOKEN_BLACKLIST + token);
                if (blacklisted != null && blacklisted) {
                    chain.doFilter(request, response);
                    return;
                }
                Claims claims = jwtTokenProvider.parseToken(token);
                Long userId = Long.valueOf(claims.getSubject());

                // 用户状态校验：签名有效 ≠ 这个账号现在仍然可用。
                // selectById 经 @TableLogic 自动过滤 deleted = 0，故 null 同时覆盖
                // 「已注销」与「id 根本不存在」两种情形，无需再查 deleted。
                User user = userMapper.selectById(userId);
                if (user == null || !UserStatusEnum.ENABLED.is(user.getStatus())) {
                    log.debug("token 有效但账号不可用，本请求按未认证处理：userId={}", userId);
                    chain.doFilter(request, response);
                    return;
                }

                // 口令版本校验：签名有效 ≠ 这枚 token 是「当前口令」签发的（审计 §2.5）。
                // 「禁用即时生效」靠的是上面那次查库；但改密走的是另一条路 —— 账号状态没变，
                // 旧 token 会在剩余有效期内继续可用，也就是常说的「我改密码是为了把别人踢下线」
                // 落空的那种情形。这里拿库里刚读出来的口令哈希算一次摘要来比对。
                // 代价为零额外查询：password 列随着上面那次 selectById 已经在手上。
                String currentPasswordVersion = JwtTokenProvider.passwordVersion(user.getPassword());
                String tokenPasswordVersion = claims.get(JwtTokenProvider.CLAIM_PASSWORD_VERSION, String.class);
                if (currentPasswordVersion == null || !currentPasswordVersion.equals(tokenPasswordVersion)) {
                    // 走这条路的两种情形：① 口令改过了；② token 没有 pv 声明（本修复上线前签发的），
                    // 后者表现为一次性的「全体重新登录」—— 这是刻意的：若放行无声明 token，
                    // 就等于给改密留了一个最长 24h 的失效盲区，而那正是本次要堵的东西。
                    log.debug("token 有效但口令版本不匹配，本请求按未认证处理：userId={}", userId);
                    chain.doFilter(request, response);
                    return;
                }

                String role = claims.get("role", String.class);
                List<SimpleGrantedAuthority> authorities = role != null
                        ? List.of(new SimpleGrantedAuthority("ROLE_" + role))
                        : List.of();
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userId, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException e) {
                // token 无效 / 已过期 / subject 不是数字 —— 常规路径，不记日志。
                // 不注入认证，由鉴权层返回 401。
            } catch (Exception e) {
                // 兜底：Redis / DB 不可用等基础设施故障。**必须留痕** ——
                // 否则表现是「应用正常启动、请求一律 401」，与 token 过期在客户端看来一模一样，
                // 排查时无从下手（本项目的双 Redis 事故就是这个形态）。
                // 只记异常摘要，**不要记录 token 本身**。
                log.warn("认证过滤器异常，本请求按未认证处理：{}", e.toString());
            }
        }
        chain.doFilter(request, response);
    }
}
