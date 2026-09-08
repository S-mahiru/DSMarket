package com.dsmarket.modules.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsmarket.common.constant.RedisKeyConstant;
import com.dsmarket.common.enums.UserRoleEnum;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.auth.dto.LoginRequest;
import com.dsmarket.modules.auth.dto.LoginResponse;
import com.dsmarket.modules.auth.dto.RegisterRequest;
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

    @Override
    @Transactional
    public void register(RegisterRequest request) {
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
    public LoginResponse login(LoginRequest request) {
        // 登录限流：同一用户名 60 秒内最多尝试 5 次
        String limitKey = RedisKeyConstant.RATE_LIMIT_LOGIN + request.getUsername();
        Long attempts = redisTemplate.opsForValue().increment(limitKey);
        if (attempts != null && attempts == 1) {
            redisTemplate.expire(limitKey, 60, TimeUnit.SECONDS);
        }
        if (attempts != null && attempts > 5) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS.getCode(), "登录尝试过于频繁，请稍后再试");
        }

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername()));
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "用户名或密码错误");
        }
        if (user.getStatus() != 1) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "账号已被禁用");
        }

        // 登录成功，清除限流计数
        redisTemplate.delete(limitKey);

        // 登录日志：记录最近登录时间（IP 留待后续补充）
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername(), user.getRole());
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
