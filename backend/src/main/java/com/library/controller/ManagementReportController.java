package com.library.controller;

import com.library.common.Result;
import com.library.managementreport.ManagementReportException;
import com.library.managementreport.ManagementReportService;
import com.library.managementreport.ManagementReportContracts.*;
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
@RequestMapping("/management-reports/borrows")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ManagementReportController {
    private final ManagementReportService reports;
    @GetMapping
    public Result<Report> report(@RequestParam MultiValueMap<String,String> params) {
        validate(params);
        return Result.success(reports.report(params.getFirst("keyword"),params.getFirst("status"),params.getFirst("origin"),params.getFirst("startDate"),params.getFirst("endDate")));
    }
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam MultiValueMap<String,String> params) {
        validate(params);
        Export file=reports.export(params.getFirst("keyword"),params.getFirst("status"),params.getFirst("origin"),params.getFirst("startDate"),params.getFirst("endDate"));
        return ResponseEntity.ok().contentType(new MediaType("text","csv",StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+file.filename()+"\"")
                .header("X-Total-Count",Long.toString(file.total())).header("X-As-Of",file.asOf())
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,"Content-Disposition, X-Total-Count, X-As-Of").body(file.bytes());
    }
    private static void validate(MultiValueMap<String,String> params) {
        for(String key:params.keySet())
            if(!Set.of("keyword","status","origin","startDate","endDate").contains(key)||params.get(key).size()!=1)
                throw new ManagementReportException("报告仅接受关键词、状态、来源及借出日期筛选，不接受分页或重复参数");
    }
}
