package com.library.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.library.common.PageResult;
import com.library.common.Result;
import com.library.entity.Book;
import com.library.dto.InventoryReconciliation;
import com.library.dto.StatisticsSummary;
import com.library.mapper.BookMapper;
import com.library.service.StatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "库存管理", description = "库存查询接口")
@RestController
@RequestMapping("/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final BookMapper bookMapper;
    private final StatisticsService statisticsService;

    @Operation(summary = "获取库存统计")
    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('ADMIN', 'LIBRARIAN')")
    public Result<Map<String, Object>> getStats() {
        StatisticsSummary summary = statisticsService.getSummary();
        Map<String, Object> stats = summary.toMap();
        stats.put("totalBooks", summary.getCatalogTitles());
        stats.put("availableBooks", summary.getAvailableTitles());
        stats.put("borrowedBooks", summary.getBorrowedCopies());
        stats.put("lowStockBooks", summary.getLowStockTitles());
        return Result.success(stats);
    }

    @Operation(summary = "核对汇总库存与已登记实体册，仅列有差异书目")
    @GetMapping("/reconciliation")
    @PreAuthorize("hasAnyRole('ADMIN', 'LIBRARIAN')")
    public Result<InventoryReconciliation> getReconciliation() {
        return Result.success(statisticsService.getReconciliation());
    }

    @Operation(summary = "获取库存列表")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'LIBRARIAN')")
    public Result<PageResult<Book>> getList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<Book> pageParam = new Page<>(page, size);
        IPage<Book> result = bookMapper.selectPage(pageParam,
                new LambdaQueryWrapper<Book>().eq(Book::getStatus, 1)
        );
        return Result.success(new PageResult<>(result.getRecords(), result.getTotal(), (long) page, (long) size));
    }
}
