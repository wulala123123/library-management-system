package com.library.catalogpublication;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.library.catalogattributes.CatalogAttributeSupport.obj;
@RestControllerAdvice(assignableTypes=PublicationController.class)
@Order(1)
public class PublicationInputHandler {
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingServletRequestParameterException.class}) public ResponseEntity<?> invalid(Exception e){return ResponseEntity.badRequest().body(obj("code",400,"message","出版资料输入无效","data",obj("reasonCode","INVALID_INPUT","requestId",null,"bookId",null)));}
}
