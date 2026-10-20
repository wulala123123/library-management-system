package com.library.finance;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static com.library.finance.FinanceSupport.*;

@RestController
@RequestMapping("/member-finance")
public class FinanceWriteController {
    private final FinanceWriteService service;
    public FinanceWriteController(FinanceWriteService service){this.service=service;}
    private JsonNode command(String key,String kind,HttpServletRequest r,Authentication a) {
        service.authorize(a); // Never read a command body for an unauthorized caller.
        if(r.getQueryString()!=null)throw failure(400,"UNKNOWN_FIELD");
        var body=FinanceCommands.decode(r,kind);r.setAttribute("finance.operationKey",body.path("operationKey").asText());
        var result=service.command(key,kind,body,a);
        if(result.failure()!=null)throw result.failure();return envelope(result.data());
    }
    @PostMapping("/operations") public JsonNode reserve(HttpServletRequest r,Authentication a){return command(null,"reserve",r,a);}
    @PostMapping("/operations/by-key/{key}/{kind:claim|draft|preview|confirm|cancel|resolve}")
    public JsonNode update(@PathVariable String key,@PathVariable String kind,HttpServletRequest r,Authentication a){return command(key,kind,r,a);}
    @GetMapping("/operations/active") public JsonNode active(HttpServletRequest r,Authentication a){return envelope(service.observe(null,null,r.getQueryString(),a));}
    @GetMapping("/operations/by-key/{key}") public JsonNode byKey(@PathVariable String key,HttpServletRequest r,Authentication a){return envelope(service.observe(key,null,r.getQueryString(),a));}
    @GetMapping("/requests/{request}") public JsonNode request(@PathVariable String request,HttpServletRequest r,Authentication a){return envelope(service.observe(null,request,r.getQueryString(),a));}
}
