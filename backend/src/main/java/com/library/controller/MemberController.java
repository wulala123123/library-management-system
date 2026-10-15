package com.library.controller;

import com.library.common.PageResult;
import com.library.common.Result;
import com.library.dto.*;
import com.library.service.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/members")
@RequiredArgsConstructor
public class MemberController {
    private final MemberService members;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    public Result<PageResult<MemberSummary>> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer accountStatus, @RequestParam(required = false) String serviceStatus) {
        return Result.success(members.list(page, size, keyword, accountStatus, serviceStatus));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('READER')")
    public Result<MemberSummary> me(Authentication auth) {
        return Result.success(members.summary((Long) auth.getPrincipal()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    public Result<MemberSummary> detail(@PathVariable Long id, Authentication auth) {
        return Result.success(isAdmin(auth) ? members.detail(id) : members.summary(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<MemberDetail> create(@Valid @RequestBody MemberCreateRequest request, Authentication auth) {
        return Result.success(members.create(request, (Long) auth.getPrincipal()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<MemberDetail> update(@PathVariable Long id, @Valid @RequestBody MemberUpdateRequest request, Authentication auth) {
        return Result.success(members.update(id, request, (Long) auth.getPrincipal()));
    }

    @GetMapping("/{id}/source-preview")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> sourcePreview(@PathVariable Long id) {
        return Result.success(members.sourcePreview(id));
    }

    @GetMapping("/{id}/changes")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PageResult<Map<String, Object>>> changes(@PathVariable Long id,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int size) {
        return Result.success(members.changes(id, page, size));
    }

    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
