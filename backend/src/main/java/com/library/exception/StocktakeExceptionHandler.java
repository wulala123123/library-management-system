package com.library.exception;
import com.library.common.Result;
import com.library.controller.StocktakeController;
import com.library.stocktake.StocktakeSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
@RestControllerAdvice(assignableTypes=StocktakeController.class) @Order(Ordered.HIGHEST_PRECEDENCE)
public class StocktakeExceptionHandler {
    @ExceptionHandler(StocktakeSupport.Failure.class) public ResponseEntity<Result<JsonNode>>business(StocktakeSupport.Failure e){Result<JsonNode>r=Result.error(e.status,e.code);r.setData(StocktakeSupport.obj("reasonCode",e.code,"sessionId",e.sessionId,"entityKey",e.entityKey));return ResponseEntity.status(e.status).body(r);}
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,MissingServletRequestParameterException.class,IllegalArgumentException.class}) public ResponseEntity<Result<JsonNode>>input(Exception e){return business(StocktakeSupport.fail(400,"INVALID_INPUT"));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class) public ResponseEntity<Result<JsonNode>>database(Exception e){return business(StocktakeSupport.fail(500,"STOCKTAKE_SAVE_FAILED"));}
}
