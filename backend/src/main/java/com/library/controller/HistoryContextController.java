package com.library.controller;

import com.library.common.PageResult;
import com.library.common.Result;
import com.library.history.HistoryContextService;
import com.library.history.HistoryContextContracts.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/history-context")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class HistoryContextController {
    private final HistoryContextService history;

    @GetMapping("/{kind}/{id}")
    public Result<Context> context(@PathVariable String kind, @PathVariable long id) {
        return Result.success(history.context(kind, id));
    }

    @GetMapping("/{kind}/{id}/records")
    public Result<PageResult<Archive>> records(@PathVariable String kind, @PathVariable long id,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "ALL") String type) {
        return Result.success(history.records(kind, id, page, size, type));
    }

    @GetMapping("/{kind}/{id}/records/{recordId}")
    public Result<ArchiveDetail> record(@PathVariable String kind, @PathVariable long id,
                                      @PathVariable long recordId) {
        return Result.success(history.record(kind, id, recordId));
    }
}
