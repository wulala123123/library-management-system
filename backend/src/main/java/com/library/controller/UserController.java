package com.library.controller;

import com.library.common.PageResult;
import com.library.common.Result;
import com.library.dto.UserRequest;
import com.library.entity.User;
import com.library.service.PermissionLogService;
import com.library.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@Tag(name = "用户管理", description = "用户CRUD接口")
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final com.library.mapper.UserMapper userMapper;
    private final PermissionLogService permissionLogService;
    private final com.library.service.MemberService memberService;

    public record BorrowerSummary(Long id, String username, String realName, String phone, String cardNo) {}

    @GetMapping("/borrowers")
    @PreAuthorize("hasAnyRole('ADMIN', 'LIBRARIAN')")
    public Result<PageResult<BorrowerSummary>> searchBorrowers(@RequestParam(required = false) String keyword) {
        var page = memberService.list(1, 20, keyword, 1, null);
        var summaries = page.getRecords().stream()
                .map(user -> new BorrowerSummary(user.getId(), user.getUsername(), user.getRealName(), user.getPhone(), user.getCardNo())).toList();
        return Result.success(new PageResult<>(summaries, page.getTotal(), page.getPage(), page.getSize()));
    }

    @Operation(summary = "分页查询用户列表")
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PageResult<User>> getPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Integer status) {
        return Result.success(userService.getPage(page, size, keyword, role, status));
    }

    @Operation(summary = "根据ID查询用户")
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<User> getById(@PathVariable Long id) {
        return Result.success(userService.getById(id));
    }

    @Operation(summary = "新增用户")
    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<User> create(@Valid @RequestBody UserRequest request, Authentication authentication) {
        User user = userService.create(request);
        Long operatorId = (Long) authentication.getPrincipal();
        String operatorName = authentication.getAuthorities().iterator().next().getAuthority().replace("ROLE_", "");
        permissionLogService.log(operatorId, operatorName, user.getId(), user.getRealName(),
                "CREATE_USER", null, user.getRole(), "创建用户，角色: " + user.getRole());
        return Result.success(user);
    }

    @Operation(summary = "更新用户")
    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<User> update(@Valid @RequestBody UserRequest request, Authentication authentication) {
        User oldUser = userMapper.lockById(request.getId());
        String oldRole = oldUser != null ? oldUser.getRole() : null;
        User user = userService.update(request);
        Long operatorId = (Long) authentication.getPrincipal();
        String operatorName = authentication.getAuthorities().iterator().next().getAuthority().replace("ROLE_", "");
        String action = "UPDATE_USER";
        String detail = "更新用户信息";
        if (request.getRole() != null && !request.getRole().equals(oldRole)) {
            action = "CHANGE_ROLE";
            detail = "角色变更: " + oldRole + " -> " + request.getRole();
        }
        permissionLogService.log(operatorId, operatorName, user.getId(), user.getRealName(),
                action, oldRole, user.getRole(), detail);
        return Result.success(user);
    }

    @Operation(summary = "删除用户")
    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id, Authentication authentication) {
        User user = userMapper.lockById(id);
        Long operatorId = (Long) authentication.getPrincipal();
        String operatorName = authentication.getAuthorities().iterator().next().getAuthority().replace("ROLE_", "");
        permissionLogService.log(operatorId, operatorName, id, user != null ? user.getRealName() : "",
                "DELETE_USER", user != null ? user.getRole() : null, null, "删除用户");
        userService.delete(id);
        return Result.success();
    }

    @Operation(summary = "更新用户状态")
    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status, Authentication authentication) {
        User user = userMapper.lockById(id);
        Long operatorId = (Long) authentication.getPrincipal();
        String operatorName = authentication.getAuthorities().iterator().next().getAuthority().replace("ROLE_", "");
        String action = status == 1 ? "ENABLE_USER" : "DISABLE_USER";
        permissionLogService.log(operatorId, operatorName, id, user != null ? user.getRealName() : "",
                action, user != null ? user.getRole() : null, user != null ? user.getRole() : null,
                status == 1 ? "启用用户" : "禁用用户");
        userService.updateStatus(id, status);
        return Result.success();
    }

    @Operation(summary = "重置用户密码")
    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @PutMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, String>> resetPassword(@PathVariable Long id, Authentication authentication) {
        User user = userMapper.lockById(id);
        Long operatorId = (Long) authentication.getPrincipal();
        String operatorName = authentication.getAuthorities().iterator().next().getAuthority().replace("ROLE_", "");
        permissionLogService.log(operatorId, operatorName, id, user != null ? user.getRealName() : "",
                "RESET_PASSWORD", null, null, "重置用户密码");
        String newPassword = userService.resetPassword(id);
        Map<String, String> result = new java.util.HashMap<>();
        result.put("password", newPassword);
        return Result.success(result);
    }

    @Operation(summary = "管理员修改用户密码")
    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @PutMapping("/{id}/change-password")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> adminChangePassword(@PathVariable Long id, @RequestBody Map<String, String> body, Authentication authentication) {
        String newPassword = body.get("newPassword");
        if (newPassword == null || newPassword.isEmpty()) {
            return Result.error(400, "新密码不能为空");
        }
        User user = userMapper.lockById(id);
        Long operatorId = (Long) authentication.getPrincipal();
        String operatorName = authentication.getAuthorities().iterator().next().getAuthority().replace("ROLE_", "");
        permissionLogService.log(operatorId, operatorName, id, user != null ? user.getRealName() : "",
                "ADMIN_CHANGE_PASSWORD", null, null, "管理员修改用户密码");
        userService.adminChangePassword(id, newPassword);
        return Result.success();
    }
}
