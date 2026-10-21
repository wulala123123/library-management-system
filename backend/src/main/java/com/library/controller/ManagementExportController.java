package com.library.controller;

import com.library.managementexport.ManagementExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/management-exports")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ManagementExportController {
    private final ManagementExportService exports;
    @GetMapping("/members")
    public ResponseEntity<byte[]> members(@RequestParam MultiValueMap<String,String> query){return file(exports.members(query));}
    @GetMapping("/books")
    public ResponseEntity<byte[]> books(@RequestParam MultiValueMap<String,String> query){return file(exports.books(query));}
    @GetMapping("/inventory")
    public ResponseEntity<byte[]> inventory(@RequestParam MultiValueMap<String,String> query){return file(exports.inventory(query));}
    private ResponseEntity<byte[]> file(ManagementExportService.File file) {
        return ResponseEntity.ok().contentType(new MediaType("text","csv",StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+file.filename()+"\"")
                .header("X-Total-Count",Long.toString(file.total())).header("X-As-Of",file.asOf())
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,"Content-Disposition, X-Total-Count, X-As-Of").body(file.bytes());
    }
}
