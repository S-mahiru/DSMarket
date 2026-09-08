package com.dsmarket.modules.user.service;

import com.dsmarket.common.domain.PageResult;
import com.dsmarket.modules.user.dto.UpdatePasswordRequest;
import com.dsmarket.modules.user.dto.UpdateProfileRequest;
import com.dsmarket.modules.user.dto.UserAdminVO;
import com.dsmarket.modules.user.dto.UserVO;

public interface UserService {

    UserVO getProfile(Long userId);

    UserVO updateProfile(Long userId, UpdateProfileRequest request);

    void updatePassword(Long userId, UpdatePasswordRequest request);

    /** 管理端：用户分页（关键词：用户名/昵称/手机号） */
    PageResult<UserAdminVO> adminPage(String keyword, long page, long size);

    /** 管理端：启用/禁用用户（禁止操作自己） */
    void adminUpdateStatus(Long operatorId, Long userId, Integer status);
}
