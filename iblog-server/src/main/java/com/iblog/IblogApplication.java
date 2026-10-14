package com.iblog;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * iblog 个人博客后端启动类。
 */
@SpringBootApplication
@MapperScan("com.iblog.module.mapper")
public class IblogApplication {

    public static void main(String[] args) {
        SpringApplication.run(IblogApplication.class, args);
    }

    /**
     * 密码加密器（BCrypt）。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}