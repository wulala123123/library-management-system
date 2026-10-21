package com.library.managementexport;

import com.library.catalogattributes.CatalogAttributeSupport;
import com.library.common.Result;
import com.library.controller.ManagementExportController;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-1)
@RestControllerAdvice(assignableTypes=ManagementExportController.class)
public class ManagementExportExceptionHandler {
    @ExceptionHandler(ManagementExportException.class)
    public ResponseEntity<Result<Void>> handle(ManagementExportException error) {
        return ResponseEntity.status(error.status()).body(Result.error(error.status(),error.getMessage()));
    }
    @ExceptionHandler(CatalogAttributeSupport.Failure.class)
    public ResponseEntity<Result<Void>> catalog(CatalogAttributeSupport.Failure error) {
        return ResponseEntity.status(error.status).body(Result.error(error.status,
                error.status==400?"书目筛选参数无效":"书目导出失败，请稍后重试"));
    }
}
