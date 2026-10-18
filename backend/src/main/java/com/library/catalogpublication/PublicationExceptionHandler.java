package com.library.catalogpublication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.library.catalogattributes.CatalogAttributeSupport.obj;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicationExceptionHandler {
    @ExceptionHandler(PublicationSupport.Failure.class) public ResponseEntity<?> failure(PublicationSupport.Failure e){return ResponseEntity.status(e.status).body(obj("code",e.status,"message","出版资料操作失败，请核对输入或查询原请求","data",obj("reasonCode",e.code,"requestId",e.requestId,"bookId",e.bookId)));}
}
