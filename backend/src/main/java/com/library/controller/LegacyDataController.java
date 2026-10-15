package com.library.controller;

import com.library.common.PageResult;
import com.library.common.Result;
import com.library.dto.LegacyMemberResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/legacy-data")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class LegacyDataController {
    private final JdbcTemplate jdbc;

    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batches", jdbc.queryForList("SELECT batch_key, summary_json, imported_time FROM migration_batch ORDER BY imported_time DESC"));
        result.put("types", jdbc.queryForList("SELECT record_type, COUNT(*) AS total FROM legacy_record GROUP BY record_type"));
        return Result.success(result);
    }

    @GetMapping("/members")
    public Result<List<LegacyMemberResponse>> members() {
        return Result.success(jdbc.queryForList("SELECT p.*, u.real_name, u.phone, u.username FROM member_profile p JOIN sys_user u ON p.user_id = u.id WHERE u.deleted = 0 ORDER BY p.service_end DESC, p.user_id")
                .stream().map(LegacyMemberResponse::new).toList());
    }

    @GetMapping("/records")
    public Result<PageResult<Map<String, Object>>> records(
            @RequestParam(defaultValue = "ORDER") String type,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long userId) {
        if (!Set.of("ORDER", "MEMBER", "PLAN", "BOOK").contains(type)) throw new IllegalArgumentException("资料类型不正确");
        int safePage = Math.max(1, page), safeSize = Math.min(100, Math.max(1, size));
        String where = " WHERE record_type = ? AND (display_name LIKE ? OR source_key LIKE ? OR summary LIKE ?)";
        List<Object> args = new ArrayList<>(List.of(type, "%" + keyword + "%", "%" + keyword + "%", "%" + keyword + "%"));
        if (userId != null) { where += " AND user_id = ?"; args.add(userId); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM legacy_record" + where, Long.class, args.toArray());
        args.add(safeSize); args.add(((long) safePage - 1) * safeSize);
        var rows = jdbc.queryForList("SELECT id, source_key, display_name, summary, user_id, book_id, source_time FROM legacy_record" + where + " ORDER BY source_time DESC, id DESC LIMIT ? OFFSET ?", args.toArray());
        return Result.success(new PageResult<>(rows, total, (long) safePage, (long) safeSize));
    }

    @GetMapping("/records/{id}")
    public Result<Map<String, Object>> record(@PathVariable Long id) {
        var rows = jdbc.queryForList("SELECT * FROM legacy_record WHERE id = ?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("资料不存在");
        return Result.success(rows.get(0));
    }
}
