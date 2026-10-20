package com.library.circulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.library.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static com.library.circulation.CirculationSupport.*;

@RestController
@RequestMapping("/circulation")
public class CirculationController {
    private final CirculationService service;
    public CirculationController(CirculationService service){this.service=service;}
    // Raw bodies and path strings are deliberately decoded only after the current DB role.
    private long access(Authentication a){long id=actor(a);service.authorize(id);return id;}
    @PostMapping("/requests/{key}/preview") public Result<JsonNode> preview(@PathVariable String key,HttpServletRequest r,Authentication a){long id=access(a);noQuery(r.getParameterMap());return Result.success(service.preview(key,body(r),id));}
    @GetMapping("/requests/{key}") public Result<JsonNode> get(@PathVariable String key,HttpServletRequest r,Authentication a){long id=access(a);noQuery(r.getParameterMap());return Result.success(service.get(key,id));}
    @PostMapping("/requests/{key}/confirm") public Result<JsonNode> confirm(@PathVariable String key,HttpServletRequest r,Authentication a){long id=access(a);noQuery(r.getParameterMap());return Result.success(service.confirm(key,body(r),id));}
    @PostMapping("/requests/{key}/cancel") public Result<JsonNode> cancel(@PathVariable String key,HttpServletRequest r,Authentication a){long id=access(a);noQuery(r.getParameterMap());return Result.success(service.cancel(key,body(r),id));}
    @GetMapping("/orders") public Result<JsonNode> list(HttpServletRequest r,Authentication a){long id=access(a);return Result.success(service.orders(r.getParameterMap(),id));}
    @GetMapping("/orders/{orderId}") public Result<JsonNode> detail(@PathVariable String orderId,HttpServletRequest r,Authentication a){long id=access(a);noQuery(r.getParameterMap());return Result.success(service.order(queryNumber(orderId),id));}
}
