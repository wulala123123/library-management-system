package com.library.controller;

import com.library.common.Result;
import com.library.dto.InventoryIntakeContracts.*;
import com.library.service.InventoryIntakeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/inventory-intakes")
@PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
@RequiredArgsConstructor
public class InventoryIntakeController {
    private final InventoryIntakeService intakes;
    @PostMapping("/previews")
    public Result<View> preview(@RequestBody PreviewRequest request, Authentication auth) { return Result.success(intakes.preview(request, (Long) auth.getPrincipal())); }
    @PostMapping("/{id}/confirm")
    public Result<Receipt> confirm(@PathVariable Long id, @RequestBody ConfirmRequest request, Authentication auth) { return Result.success(intakes.confirm(id, request, (Long) auth.getPrincipal())); }
    @GetMapping("/by-key")
    public Result<View> byKey(@RequestParam String key, Authentication auth) { return Result.success(intakes.byKey(key, (Long) auth.getPrincipal())); }
    @GetMapping("/{id}")
    public Result<View> get(@PathVariable Long id, Authentication auth) { return Result.success(intakes.get(id, (Long) auth.getPrincipal())); }
}
