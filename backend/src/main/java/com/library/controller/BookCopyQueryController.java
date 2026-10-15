package com.library.controller;

import com.library.common.PageResult;
import com.library.common.Result;
import com.library.dto.BookCopyQueryContracts.*;
import com.library.service.BookCopyQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/book-copies")
@PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
@RequiredArgsConstructor
public class BookCopyQueryController {
    private final BookCopyQueryService service;

    @GetMapping
    public Result<PageResult<CopyRow>> copies(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) Long bookId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String location,
            @RequestParam(required = false) Boolean unassignedLocation, @RequestParam(required = false) String barcode) {
        return Result.success(service.copies(page, size, keyword, bookId, status, location, unassignedLocation, barcode));
    }

    @GetMapping("/catalog-summary")
    public Result<PageResult<CatalogSummary>> catalogSummary(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) Long bookId) {
        return Result.success(service.catalogSummary(page, size, keyword, bookId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Result<CopyDetail>> detail(@PathVariable Long id) {
        CopyDetail detail = service.detail(id);
        return detail == null ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.error(404, "实体册不存在")) : ResponseEntity.ok(Result.success(detail));
    }
}
