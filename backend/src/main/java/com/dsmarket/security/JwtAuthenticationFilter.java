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
 * JWT 认证过滤器：提取 Bearer Token → 校验（Redis 黑名单 + 用户状态）→ 注入 SecurityContext
 *
 * <p><b>为什么验签之后还要查一次库</b>（审计 12-readiness-audit §1.1 / §2.5）：
 * JWT 是自包含的，签发之后服务端对它没有任何控制力。只验签的话，「管理员禁用账号」在既有 token
 * 的剩余有效期（{@code jwt.expiration}，默认 24h）内<b>完全不生效</b> —— 登录接口拦得住新登录，
 * 拦不住手里已经拿着 token 的人。故此处补一次状态校验，让禁用<b>立即</b>生效。</p>
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
