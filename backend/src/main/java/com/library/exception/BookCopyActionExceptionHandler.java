package com.library.exception;

import com.library.common.Result;
import com.library.dto.BookCopyActionContracts.ErrorData;
import com.library.service.BookCopyActionException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice @Order(Ordered.HIGHEST_PRECEDENCE)
public class BookCopyActionExceptionHandler {
    @ExceptionHandler(BookCopyActionException.class)
    public ResponseEntity<Result<ErrorData>> action(BookCopyActionException e){return response(e.status(),e.reasonCode(),e.getMessage(),e.requestId(),e.copyId(),e.ordinal());}
    static ResponseEntity<Result<ErrorData>> response(int code,String reason,String message,Long request,Long copy,Integer ordinal){Result<ErrorData> result=Result.error(code,message);result.setData(new ErrorData(reason,request,copy,ordinal));return ResponseEntity.status(code).body(result);}
}
