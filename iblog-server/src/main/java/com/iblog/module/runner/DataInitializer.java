package com.iblog.module.runner;

import com.iblog.module.entity.User;
import com.iblog.module.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次启动时初始化默认管理员账号，避免把 BCrypt 密文写死在 SQL 脚本里。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin123";

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        if (userService.getByUsername(DEFAULT_USERNAME) != null) {
            return;
        }
        User user = new User();
        user.setUsername(DEFAULT_USERNAME);
        user.setPassword(passwordEncoder.encode(DEFAULT_PASSWORD));
        user.setNickname("博主");
        user.setEmail("admin@iblog.com");
        user.setStatus(1);
        userService.save(user);
        log.info("==========================================================");
        log.info(" 已初始化默认管理员账号：{} / {}", DEFAULT_USERNAME, DEFAULT_PASSWORD);
        log.info(" 请登录后立即修改密码！");
        log.info("==========================================================");
    }
}
