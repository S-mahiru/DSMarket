package com.dsmarket.config;

import com.dsmarket.security.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // ASYNC/ERROR 二次 dispatch 放行：stateless JWT 下过滤器仅在首次 REQUEST dispatch
                        // 校验鉴权；SSE(SseEmitter) 结束时 Tomcat 会 asyncDispatch 重进过滤器链，若在此再判
                        // 鉴权会因无 SecurityContext 被拒 → 已提交的流被异常关闭（客户端读到半截 SSE）。
                        // 权限已在 REQUEST dispatch 校验，ASYNC/ERROR 不再重复授权。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        // 公开接口：登录注册、商品浏览、静态资源、API文档
                        .requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/register",
                                "/api/v1/categories/**",
                                "/api/v1/products/**",
                                "/uploads/**",
                                "/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/swagger-resources/**"
                        ).permitAll()
                        // 管理端：需 ADMIN 角色
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // 其余接口：需登录
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> {
                    // 未认证 → 401
                    ex.authenticationEntryPoint((request, response, authException) -> {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write("{\"code\":401,\"message\":\"未登录或Token已过期\",\"data\":null}");
                    });
                    // 已认证但无权限 → 403（必须显式配置：默认 handler 的 sendError(403) 会走 error dispatch 被二次处理成 401）
                    ex.accessDeniedHandler((request, response, accessDeniedException) -> {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write("{\"code\":403,\"message\":\"无权限访问\",\"data\":null}");
                    });
                })
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
