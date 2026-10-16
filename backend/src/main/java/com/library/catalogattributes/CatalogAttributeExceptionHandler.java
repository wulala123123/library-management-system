package com.library.catalogattributes;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.library.catalogattributes.CatalogAttributeSupport.*;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CatalogAttributeExceptionHandler {
    @ExceptionHandler(Failure.class)public ResponseEntity<?> failure(Failure e){return ResponseEntity.status(e.status).body(obj("code",e.status,"message","书目属性操作失败，请核对输入或查询原请求","data",obj("reasonCode",e.code,"requestId",null,"bookId",null)));}
}
