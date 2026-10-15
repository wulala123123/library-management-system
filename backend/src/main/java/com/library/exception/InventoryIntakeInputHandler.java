package com.library.exception;

import com.library.common.Result;
import com.library.controller.BookMetadataController;
import com.library.controller.InventoryIntakeController;
import com.library.dto.InventoryIntakeContracts.ErrorData;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Never log SQL, raw barcodes or request bodies from the intake endpoints. */
@RestControllerAdvice(assignableTypes = {InventoryIntakeController.class, BookMetadataController.class})
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class InventoryIntakeInputHandler {
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, IllegalArgumentException.class})
    public ResponseEntity<Result<ErrorData>> input(Exception e) { return InventoryIntakeExceptionHandler.response(400, "INVALID_INPUT", "入库参数不符合要求，请检查输入", null); }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<ErrorData>> database(DataAccessException e) { return InventoryIntakeExceptionHandler.response(500, "INTAKE_SAVE_FAILED", "入库保存失败，请先查询办理结果后重试", null); }
}
