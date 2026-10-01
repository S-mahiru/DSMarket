package com.dsmarket.modules.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsmarket.common.constant.RedisKeyConstant;
import com.dsmarket.common.enums.UserRoleEnum;
import com.dsmarket.common.enums.UserStatusEnum;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.auth.dto.LoginRequest;
import com.dsmarket.modules.auth.dto.LoginResponse;
import com.dsmarket.modules.auth.dto.RegisterRequest;
import com.dsmarket.modules.auth.limit.AuthRateLimiter;
import com.dsmarket.modules.auth.service.AuthService;
import com.dsmarket.modules.user.dto.UserVO;
import com.dsmarket.modules.user.entity.User;
import com.dsmarket.modules.user.mapper.UserMapper;
import com.dsmarket.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RedisTemplate<String, Object> redisTemplate;
    private final AuthRateLimiter authRateLimiter;

    @Override
    @Transactional
    public void register(RegisterRequest request, String clientIp) {
        authRateLimiter.checkRegister(clientIp);
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername()));
        if (count > 0) {
            throw new BusinessException(ErrorCode.CONFLICT.getCode(), "用户名已存在");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setNickname(request.getUsername());
        user.setRole(UserRoleEnum.USER.getValue());
        user.setStatus(1);
        userMapper.insert(user);
    }

    @Override
    public LoginResponse login(LoginRequest request, String clientIp) {
        // 限流前置检查：只判"是否已被封锁"，不在这里计数（审计 §2.4）。
        // 计数放在失败分支 —— 把成功/失败的尝试一律计入，会让攻击者仅凭垃圾请求
        // 就把真用户锁在门外（反向账号锁定 DoS），而那一步不需要猜中任何口令。
        authRateLimiter.checkLogin(request.getUsername(), clientIp);

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername()));
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            // 失败才计数：账号维度记在 (账号, 来源IP) 对上，攻击者只能锁住自己那一格。
            authRateLimiter.onLoginFailure(request.getUsername(), clientIp);
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "用户名或密码错误");
        }
        // 与 JwtAuthenticationFilter 共用同一判据：禁用既要拦住"新登录"，也要让"手里的旧 token"失效。
        // 这里只做前半段，后半段在过滤器里（审计 12-readiness-audit §1.1）。
        // 注意本分支【不计失败】—— 口令是对的，被禁用不是"猜错了"。
        if (!UserStatusEnum.ENABLED.is(user.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "账号已被禁用");
        }

        // 登录成功，清掉该 (账号, IP) 对的失败计数，让本人输错几次后立刻恢复正常
        authRateLimiter.onLoginSuccess(request.getUsername(), clientIp);

        // 登录日志：记录最近登录时间（IP 留待后续补充）
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        // 把口令哈希一并签进 token（pv 声明）：日后改密 ⇒ 库里哈希变 ⇒ 旧 token 立即失效。
        // 详见 JwtTokenProvider.passwordVersion 与审计 12-readiness-audit §2.5。
        String token = jwtTokenProvider.generateToken(
                user.getId(), user.getUsername(), user.getRole(), user.getPassword());
        return new LoginResponse(token, "Bearer", jwtTokenProvider.getExpirationSeconds(), UserVO.from(user));
    }

    @Override
    public void logout(String token) {
        long ttlSeconds;
        try {
            Date expiration = jwtTokenProvider.parseToken(token).getExpiration();
            ttlSeconds = Math.max(0, (expiration.getTime() - System.currentTimeMillis()) / 1000);
        } catch (Exception e) {
            ttlSeconds = 0;
        }
        if (ttlSeconds > 0) {
            redisTemplate.opsForValue()
                    .set(RedisKeyConstant.TOKEN_BLACKLIST + token, "1", ttlSeconds, TimeUnit.SECONDS);
        }
    }
}
