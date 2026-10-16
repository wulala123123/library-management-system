package com.library.catalogidentity;

import com.library.common.Result;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = CatalogIdentityController.class)
@Order(1)
public class CatalogIdentityExceptionHandler {
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<?> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(com.library.catalogattributes.CatalogAttributeSupport.obj("code",400,"message","身份操作输入无效","data",com.library.catalogattributes.CatalogAttributeSupport.obj("reasonCode","INVALID_INPUT")));
    }
}
