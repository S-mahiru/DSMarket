package com.dsmarket.config;

import com.dsmarket.common.enums.UserRoleEnum;
import com.dsmarket.modules.user.entity.User;
import com.dsmarket.modules.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 开发环境种子数据：首次启动（用户表为空）时播种 admin / testuser。
 *
 * <p><b>为什么限定 dev</b>：本类会创建固定口令 {@code admin/admin123} 的管理员账号。
 * 生产环境若在用户表为空的全新库上启动（首次部署的常态），就会种下一个
 * 口令公开可猜的管理员 —— 谁先登录谁就是管理员。
 * 限定 {@code dev} 后，prod 起服务不再播种；空库也不再等于「有一个默认管理员」。
 *
 * <p>本类是 {@code CommandLineRunner}，因此只有被注册为 bean 才会执行；
 * {@code @Profile("dev")} 即整类不注册。默认 profile 就是 dev
 * （见 {@code application.yml} 的 {@code spring.profiles.active}），所以本地开发不受影响。
 */
@Slf4j
@Component
@Profile("dev")
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
