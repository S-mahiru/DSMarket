package com.dsmarket.modules.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.user.dto.UpdatePasswordRequest;
import com.dsmarket.modules.user.dto.UpdateProfileRequest;
import com.dsmarket.modules.user.dto.UserAdminVO;
import com.dsmarket.modules.user.dto.UserVO;
import com.dsmarket.modules.user.entity.User;
import com.dsmarket.modules.user.mapper.UserMapper;
import com.dsmarket.modules.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public UserVO getProfile(Long userId) {
        return UserVO.from(requireUser(userId));
    }

    @Override
    @Transactional
    public UserVO updateProfile(Long userId, UpdateProfileRequest request) {
        User user = requireUser(userId);
        if (request.getNickname() != null) {
            user.setNickname(request.getNickname());
        }
        if (request.getAvatar() != null) {
            user.setAvatar(request.getAvatar());
        }
        if (request.getGender() != null) {
            user.setGender(request.getGender());
        }
        userMapper.updateById(user);
        return UserVO.from(user);
    }

    @Override
    @Transactional
    public void updatePassword(Long userId, UpdatePasswordRequest request) {
        User user = requireUser(userId);
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "原密码错误");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userMapper.updateById(user);
    }

    @Override
    public PageResult<UserAdminVO> adminPage(String keyword, long page, long size) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .and(StringUtils.hasText(keyword), w -> w
                        .like(User::getUsername, keyword)
                        .or().like(User::getNickname, keyword)
                        .or().like(User::getPhone, keyword))
                .orderByDesc(User::getId);
        Page<User> p = userMapper.selectPage(new Page<>(page, size), wrapper);
        List<UserAdminVO> vos = p.getRecords().stream().map(UserAdminVO::from).toList();
        return PageResult.of(vos, p.getTotal(), page, size);
    }

    @Override
    @Transactional
    public void adminUpdateStatus(Long operatorId, Long userId, Integer status) {
        if (operatorId.equals(userId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "不能操作自己的账号");
        }
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "状态参数错误");
        }
        User user = requireUser(userId);
        if (user.getStatus() != null && user.getStatus().equals(status)) {
            return; // 幂等
        }
        user.setStatus(status);
        userMapper.updateById(user);
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "用户不存在");
        }
        return user;
    }
}
