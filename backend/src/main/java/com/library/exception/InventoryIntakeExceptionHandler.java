package com.library.exception;

import com.library.common.Result;
import com.library.dto.InventoryIntakeContracts.ErrorData;
import com.library.service.InventoryIntakeException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InventoryIntakeExceptionHandler {
    @ExceptionHandler(InventoryIntakeException.class)
    public ResponseEntity<Result<ErrorData>> business(InventoryIntakeException e) {
        return response(e.status(), e.reasonCode(), e.getMessage(), e.intakeId());
    }
    static ResponseEntity<Result<ErrorData>> response(int status, String reason, String message, Long id) {
        Result<ErrorData> result = Result.error(status, message); result.setData(new ErrorData(reason, id));
        return ResponseEntity.status(status).body(result);
    }
}
