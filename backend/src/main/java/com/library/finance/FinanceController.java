package com.library.finance;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import static com.library.finance.FinanceSupport.*;

@RestController
@RequestMapping("/members/{memberId}/finance")
public class FinanceController {
    private final FinanceReadService service;
    public FinanceController(FinanceReadService service) { this.service=service; }
    @GetMapping public JsonNode finance(@PathVariable String memberId,@RequestParam MultiValueMap<String,String> q,Authentication a) {
        return envelope(service.finance(memberId,q,a));
    }
    @GetMapping("/source") public JsonNode source(@PathVariable String memberId,@RequestParam MultiValueMap<String,String> q,Authentication a) {
        return envelope(service.source(memberId,q,a));
    }
    @GetMapping("/entries") public JsonNode entries(@PathVariable String memberId,@RequestParam MultiValueMap<String,String> q,Authentication a) {
        return envelope(service.entries(memberId,q,a));
    }
}
