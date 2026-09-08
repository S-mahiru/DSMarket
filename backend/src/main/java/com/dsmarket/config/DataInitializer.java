package com.dsmarket.config;

import com.dsmarket.common.enums.UserRoleEnum;
import com.dsmarket.modules.user.entity.User;
import com.dsmarket.modules.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userMapper.selectCount(null) > 0) {
            return;
        }

        User admin = new User();
        admin.setUsername("admin");
        admin.setPassword(passwordEncoder.encode("admin123"));
        admin.setNickname("管理员");
        admin.setRole(UserRoleEnum.ADMIN.getValue());
        admin.setEmail("admin@dsmarket.com");
        admin.setStatus(1);
        userMapper.insert(admin);

        User testUser = new User();
        testUser.setUsername("testuser");
        testUser.setPassword(passwordEncoder.encode("test123"));
        testUser.setNickname("测试用户");
        testUser.setRole(UserRoleEnum.USER.getValue());
        testUser.setEmail("test@dsmarket.com");
        testUser.setStatus(1);
        userMapper.insert(testUser);

        log.info("种子数据初始化完成：admin / testuser");
    }
}
