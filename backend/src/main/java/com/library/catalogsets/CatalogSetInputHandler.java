package com.library.catalogsets;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice(assignableTypes=CatalogSetController.class)
@Order(1)
class CatalogSetInputHandler {
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingServletRequestParameterException.class}) public ResponseEntity<?> invalid(Exception e){return new CatalogSetExceptionHandler().failure(CatalogSetSupport.failure(400,"INVALID_INPUT"));}
}
