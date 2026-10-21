package com.library.borrowquery;

import com.library.common.Result;
import com.library.controller.BorrowInquiryController;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-1)
@RestControllerAdvice(assignableTypes = BorrowInquiryController.class)
public class BorrowInquiryExceptionHandler {
    @ExceptionHandler(BorrowInquiryException.class)
    public ResponseEntity<Result<Void>> handle(BorrowInquiryException error) {
        return ResponseEntity.badRequest().body(Result.error(400, error.getMessage()));
    }
}
