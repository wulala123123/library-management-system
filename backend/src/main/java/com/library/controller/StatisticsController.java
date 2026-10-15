package com.library.controller;

import com.library.common.Result;
import com.library.entity.Book;
import com.library.entity.BookCategory;
import com.library.entity.BorrowRecord;
import com.library.dto.StatisticsSummary;
import com.library.mapper.BookCategoryMapper;
import com.library.mapper.BookMapper;
import com.library.mapper.BorrowRecordMapper;
import com.library.mapper.StatisticsMapper;
import com.library.service.StatisticsService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Tag(name = "统计报表", description = "统计数据接口")
@RestController
@RequestMapping("/statistics")
@RequiredArgsConstructor
public class StatisticsController {

    private final BookMapper bookMapper;
    private final BorrowRecordMapper borrowRecordMapper;
    private final BookCategoryMapper categoryMapper;
    private final StatisticsService statisticsService;
    private final StatisticsMapper statisticsMapper;

    @Operation(summary = "获取统计数据")
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'LIBRARIAN')")
    public Result<Map<String, Object>> getStatistics() {
        StatisticsSummary summary = statisticsService.getSummary();
        Map<String, Object> stats = summary.toMap();
        stats.put("totalBooks", summary.getCatalogTitles());
        stats.put("totalUsers", summary.getAccountCount());
        stats.put("totalBorrows", summary.getBorrowRecordCount());
        stats.put("activeBorrows", summary.getBorrowedCopies());
        return Result.success(stats);
    }

    @Operation(summary = "导出与统计汇总相同口径的基础指标 CSV")
    @GetMapping(value = "/export", produces = "text/csv")
    @PreAuthorize("hasAnyRole('ADMIN', 'LIBRARIAN')")
    public ResponseEntity<byte[]> export() {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=library-statistics.csv")
                .body(statisticsService.exportCsv().getBytes(StandardCharsets.UTF_8));
    }

    @Operation(summary = "获取借阅趋势数据（近7天）")
    @GetMapping("/borrow-trend")
    @PreAuthorize("hasAnyRole('ADMIN', 'LIBRARIAN', 'READER')")
    public Result<List<Map<String, Object>>> getBorrowTrend() {
        List<Map<String, Object>> trend = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            String label = date.format(fmt);
            Long count = statisticsMapper.countBorrowsOnDate(date);
            Map<String, Object> item = new HashMap<>();
            item.put("label", label);
            item.put("value", count);
            trend.add(item);
        }
        return Result.success(trend);
    }

    @Operation(summary = "获取分类占比数据")
    @GetMapping("/category-distribution")
    @PreAuthorize("hasAnyRole('ADMIN', 'LIBRARIAN', 'READER')")
    public Result<List<Map<String, Object>>> getCategoryDistribution() {
        List<Book> allBooks = bookMapper.selectList(
                new LambdaQueryWrapper<Book>().eq(Book::getStatus, 1)
        );
        Map<Long, Long> categoryCount = allBooks.stream()
                .filter(b -> b.getCategoryId() != null)
                .collect(Collectors.groupingBy(Book::getCategoryId, Collectors.counting()));

        long total = allBooks.size();
        List<Map<String, Object>> result = new ArrayList<>();
        String[] colors = {"#78716c", "#0d9488", "#b45309", "#be123c", "#7c3aed", "#0369a1", "#a8a29e"};
        int idx = 0;

        List<BookCategory> categories = categoryMapper.selectList(null);
        for (BookCategory cat : categories) {
            Long count = categoryCount.getOrDefault(cat.getId(), 0L);
            if (count > 0) {
                Map<String, Object> item = new HashMap<>();
                item.put("name", cat.getName());
                item.put("count", count);
                item.put("percentage", total > 0 ? Math.round(count * 100.0 / total) : 0);
                item.put("color", colors[idx % colors.length]);
                result.add(item);
                idx++;
            }
        }
        result.sort((a, b) -> Long.compare((Long) b.get("count"), (Long) a.get("count")));
        return Result.success(result);
    }

    @Operation(summary = "获取热门图书TOP10")
    @GetMapping("/hot-books")
    @PreAuthorize("hasAnyRole('ADMIN', 'LIBRARIAN', 'READER')")
    public Result<List<Map<String, Object>>> getHotBooks() {
        Map<Long, Book> activeBooks = bookMapper.selectList(
                new LambdaQueryWrapper<Book>().eq(Book::getStatus, 1))
                .stream().collect(Collectors.toMap(Book::getId, book -> book));
        if (activeBooks.isEmpty()) return Result.success(List.of());
        List<BorrowRecord> records = borrowRecordMapper.selectList(null);
        Map<Long, Long> bookBorrowCount = records.stream()
                .filter(record -> activeBooks.containsKey(record.getBookId()))
                .collect(Collectors.groupingBy(BorrowRecord::getBookId, Collectors.counting()));

        List<Map<String, Object>> result = new ArrayList<>();
        bookBorrowCount.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(10)
                .forEach(entry -> {
                    Book book = activeBooks.get(entry.getKey());
                    if (book != null) {
                        Map<String, Object> item = new HashMap<>();
                        item.put("title", book.getTitle());
                        item.put("borrowCount", entry.getValue());
                        result.add(item);
                    }
                });
        return Result.success(result);
    }
}
