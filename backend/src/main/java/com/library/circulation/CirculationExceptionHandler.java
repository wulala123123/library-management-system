package com.library.circulation;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.library.circulation.CirculationSupport.*;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CirculationExceptionHandler {
    @ExceptionHandler(Failure.class)
    public ResponseEntity<?> failure(Failure e) {
        return ResponseEntity.status(e.status).body(obj("code",e.status,"message","借还办理失败，请核对输入或恢复原请求","data",obj("schemaVersion",1,"reasonCode",e.reason)));
    }
}
