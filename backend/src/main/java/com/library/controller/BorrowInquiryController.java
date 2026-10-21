package com.library.controller;

import com.library.borrowquery.BorrowInquiryException;
import com.library.borrowquery.BorrowInquiryService;
import com.library.borrowquery.BorrowInquiryContracts.*;
import com.library.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@RestController
@RequestMapping("/borrow-inquiry")
@PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
@RequiredArgsConstructor
public class BorrowInquiryController {
    private final BorrowInquiryService inquiry;
    @GetMapping
    public Result<Page> page(@RequestParam(defaultValue="1") String page, @RequestParam(defaultValue="10") String size,
            @RequestParam(defaultValue="") String keyword, @RequestParam(defaultValue="") String status,
            @RequestParam(defaultValue="ALL") String origin) {
        return Result.success(inquiry.page(page, size, keyword, status, origin));
    }
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam MultiValueMap<String,String> params) {
        for (String key : params.keySet())
            if (!Set.of("keyword","status","origin").contains(key) || params.get(key).size() != 1)
                throw new BorrowInquiryException("导出仅接受关键词、状态和来源筛选，不接受分页参数");
        Export file = inquiry.export(params.getFirst("keyword"), params.getFirst("status"), params.getFirst("origin"));
        return ResponseEntity.ok().contentType(new MediaType("text","csv",StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .header("X-Total-Count", Long.toString(file.total())).header("X-As-Of", file.asOf())
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,"Content-Disposition, X-Total-Count, X-As-Of")
                .body(file.bytes());
    }
}
