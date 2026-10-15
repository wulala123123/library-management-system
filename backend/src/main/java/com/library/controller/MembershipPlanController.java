package com.library.controller;

import com.library.common.PageResult;
import com.library.common.Result;
import com.library.dto.MembershipPlanRequests.*;
import com.library.dto.MembershipPlanResponses.*;
import com.library.service.MembershipPlanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/membership-plans")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class MembershipPlanController {
    private final MembershipPlanService plans;

    @GetMapping
    public Result<PageResult<Summary>> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size, @RequestParam(required = false) String keyword) {
        return Result.success(plans.list(page, size, keyword));
    }
    @GetMapping("/{id}")
    public Result<Detail> detail(@PathVariable Long id) { return Result.success(plans.detail(id)); }
    @PostMapping
    public Result<Detail> create(@Valid @RequestBody Create request, Authentication auth) {
        return Result.success(plans.create(request, (Long) auth.getPrincipal()));
    }
    @PutMapping("/{id}/draft")
    public Result<Detail> draft(@PathVariable Long id, @Valid @RequestBody Draft request, Authentication auth) {
        return Result.success(plans.saveDraft(id, request, (Long) auth.getPrincipal()));
    }
    @PostMapping("/{id}/versions")
    public Result<Publication> publish(@PathVariable Long id, @Valid @RequestBody Publish request, Authentication auth) {
        return Result.success(plans.publish(id, request, (Long) auth.getPrincipal()));
    }
    @PutMapping("/{id}/selection")
    public Result<Detail> selection(@PathVariable Long id, @Valid @RequestBody Selection request, Authentication auth) {
        return Result.success(plans.select(id, request, (Long) auth.getPrincipal()));
    }
    @GetMapping("/{id}/changes")
    public Result<PageResult<Change>> changes(@PathVariable Long id, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) { return Result.success(plans.changes(id, page, size)); }
}
