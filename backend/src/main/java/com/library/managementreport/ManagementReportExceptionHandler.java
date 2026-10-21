package com.library.managementreport;

import com.library.common.Result;
import com.library.controller.ManagementReportController;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-1)
@RestControllerAdvice(assignableTypes = ManagementReportController.class)
public class ManagementReportExceptionHandler {
    @ExceptionHandler(ManagementReportException.class)
    public ResponseEntity<Result<Void>> handle(ManagementReportException error) {
        return ResponseEntity.badRequest().body(Result.error(400, error.getMessage()));
    }
}
