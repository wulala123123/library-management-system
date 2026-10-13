package com.library.controller;

import com.library.common.Result;
import com.library.dto.LoginRequest;
import com.library.dto.LoginResponse;
import com.library.dto.RegisterRequest;
import com.library.entity.User;
import com.library.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;


@Tag(name = "认证管理", description = "登录注册相关接口")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(userService.login(request));
    }

    @Operation(summary = "读者注册")
    @PostMapping("/register")
    public Result<User> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(userService.register(request));
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/me")
    public Result<User> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Result.error(401, "未登录");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Long userId) {
            User user = userService.getById(userId);
            if (user != null) {
                user.setPassword(null);
                return Result.success(user);
            }
        }
        return Result.error(401, "用户信息获取失败");
    }
}
