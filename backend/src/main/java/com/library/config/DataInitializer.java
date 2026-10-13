package com.library.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.library.entity.User;
import com.library.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${bootstrap.admin.username:}")
    private String username;
    @Value("${bootstrap.admin.password:}")
    private String password;

    @Override
    @Transactional
    public void run(String... args) {
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getRole, "ADMIN")) > 0) return;
        if (username.isBlank() || password.length() < 12) {
            throw new IllegalStateException("首次初始化必须设置 ADMIN_USERNAME 和至少12位的 ADMIN_PASSWORD");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, username)) > 0) {
            throw new IllegalStateException("初始化管理员用户名已被占用");
        }
        User admin = new User();
        admin.setUsername(username);
        admin.setPassword(passwordEncoder.encode(password));
        admin.setRealName("系统管理员");
        admin.setRole("ADMIN");
        admin.setStatus(1);
        userMapper.insert(admin);
    }
}
