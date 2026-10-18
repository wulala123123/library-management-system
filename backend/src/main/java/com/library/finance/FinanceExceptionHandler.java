package com.library.finance;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.library.finance.FinanceSupport.*;

@RestControllerAdvice(assignableTypes=FinanceController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FinanceExceptionHandler {
    @ExceptionHandler(Failure.class) public ResponseEntity<?> failure(Failure e) { return ResponseEntity.status(e.status).body(error(e.status,e.code)); }
    @ExceptionHandler(Exception.class) public ResponseEntity<?> unexpected(Exception e) {
        // Do not log JDBC parameters, source values or protected evidence.
        return ResponseEntity.status(500).body(error(500,"INTERNAL_ERROR"));
    }
}
