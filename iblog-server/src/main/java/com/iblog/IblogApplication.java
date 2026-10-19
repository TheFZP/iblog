package com.iblog;

import com.iblog.module.runner.DatabaseBootstrap;
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
// 行情模块的 Mapper 放在 com.iblog.module.stock.mapper（跟 stock 模块的其他类在一起），
// 不在下面这个包下，所以必须显式列上 —— 否则启动时报
// "No qualifying bean of type 'StockSettingMapper' available"。
@MapperScan({"com.iblog.module.mapper", "com.iblog.module.stock.mapper"})
public class IblogApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(IblogApplication.class);
        // 在容器启动之前完成建库 / 建表 / 自检，避免「Unknown database」淹没在几百行堆栈里
        application.addListeners(new DatabaseBootstrap());
        application.run(args);
    }

    /**
     * 密码加密器（BCrypt）。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}