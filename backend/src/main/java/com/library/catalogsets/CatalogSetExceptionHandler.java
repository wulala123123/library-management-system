package com.library.catalogsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.library.catalogattributes.CatalogAttributeSupport.obj;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CatalogSetExceptionHandler {
    @ExceptionHandler(CatalogSetSupport.Failure.class) public ResponseEntity<?> failure(CatalogSetSupport.Failure e){return ResponseEntity.status(e.status).body(obj("code",e.status,"message","套装目录操作失败，请核对输入或查询原请求","data",obj("schemaVersion",1,"reasonCode",e.code,"requestId",e.requestId,"setId",e.setId,"bookId",e.bookId,"membershipId",e.membershipId)));}
}
