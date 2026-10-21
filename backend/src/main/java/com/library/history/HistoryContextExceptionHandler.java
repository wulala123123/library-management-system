package com.library.history;

import com.library.common.Result;
import com.library.controller.HistoryContextController;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-1)
@RestControllerAdvice(assignableTypes = HistoryContextController.class)
public class HistoryContextExceptionHandler {
    @ExceptionHandler(HistoryContextException.class)
    public ResponseEntity<Result<Void>> handle(HistoryContextException error) {
        return ResponseEntity.status(error.status()).body(Result.error(error.status(), error.getMessage()));
    }
}
