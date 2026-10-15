package com.library.controller;

import com.library.common.PageResult;
import com.library.common.Result;
import com.library.dto.MemberEntitlementContracts.*;
import com.library.service.MemberEntitlementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/members")
@RequiredArgsConstructor
public class MemberEntitlementController {
    private final MemberEntitlementService service;

    @GetMapping("/{id}/entitlement")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    public Result<?> current(@PathVariable Long id, Authentication auth) {
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return Result.success(admin ? service.current(id) : service.summary(id));
    }
    @GetMapping("/me/entitlement")
    @PreAuthorize("hasRole('READER')")
    public Result<ReaderView> me(Authentication auth) { return Result.success(service.reader((Long) auth.getPrincipal())); }
    @PostMapping("/{id}/entitlement/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Preview> preview(@PathVariable Long id, @Valid @RequestBody Input input) { return Result.success(service.preview(id, input)); }
    @PostMapping("/{id}/entitlement/confirm")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<com.library.dto.MemberEntitlementContracts.Result> confirm(@PathVariable Long id, @Valid @RequestBody Confirmation input, Authentication auth) {
        return Result.success(service.confirm(id, input, (Long) auth.getPrincipal()));
    }
    @GetMapping("/{id}/entitlement/history")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PageResult<Snapshot>> history(@PathVariable Long id, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) { return Result.success(service.history(id, page, size)); }
}
