package com.library.finance;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.library.finance.FinanceSupport.*;

@RestControllerAdvice(assignableTypes={FinanceController.class,FinanceWriteController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FinanceExceptionHandler {
    @ExceptionHandler(Failure.class) public ResponseEntity<?> failure(Failure e) { return ResponseEntity.status(e.status).body(error(e)); }
    @ExceptionHandler(Exception.class) public ResponseEntity<?> unexpected(Exception e,jakarta.servlet.http.HttpServletRequest request) {
        // Do not log JDBC parameters, source values or protected evidence.
        if(isFinancePath(request)&&!isReadPath(request)) {
            var body=error(FinanceSupport.failure(500,"UNKNOWN_RESULT"));Object key=request.getAttribute("finance.operationKey");
            if(key instanceof String value)((com.fasterxml.jackson.databind.node.ObjectNode)body.get("data")).put("operationKey",value);
            return ResponseEntity.status(500).body(body);
        }
        return ResponseEntity.status(500).body(error(500,"INTERNAL_ERROR"));
    }
}
