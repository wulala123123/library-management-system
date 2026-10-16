package com.library.catalogattributes;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.library.catalogattributes.CatalogAttributeSupport.*;

@RestControllerAdvice(assignableTypes=CatalogAttributeController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CatalogAttributeInputHandler {
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<?> invalidInput(Exception ignored){return new CatalogAttributeExceptionHandler().failure(fail(400,"INVALID_INPUT"));}
}
