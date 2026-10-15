package com.library.controller;

import com.library.common.Result;
import com.library.dto.InventoryIntakeContracts.*;
import com.library.service.IntakeCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/books/{id}/metadata")
@PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
@RequiredArgsConstructor
public class BookMetadataController {
    private final IntakeCatalog catalog;
    @GetMapping public Result<MetadataView> get(@PathVariable Long id) { return Result.success(catalog.view(id)); }
    @PutMapping public Result<MetadataView> update(@PathVariable Long id, @RequestBody MetadataUpdate request) { return Result.success(catalog.update(id, request)); }
}
