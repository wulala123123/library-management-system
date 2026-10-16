package com.library.exception;

import com.library.common.Result;
import com.library.controller.BookCopyActionController;
import com.library.dto.BookCopyActionContracts.ErrorData;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes={BookCopyActionController.class,com.library.controller.LocationAddressController.class,com.library.controller.LocationStructureEventController.class,com.library.controller.BookshelfController.class}) @Order(Ordered.HIGHEST_PRECEDENCE+1)
public class BookCopyActionInputHandler {
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,MissingServletRequestParameterException.class,IllegalArgumentException.class})
    public ResponseEntity<Result<ErrorData>> input(Exception e){return BookCopyActionExceptionHandler.response(400,"INVALID_INPUT","逐册动作参数不符合要求",null,null,null);}
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<ErrorData>> database(DataAccessException e){return BookCopyActionExceptionHandler.response(500,"ACTION_SAVE_FAILED","动作保存失败，请先查询原办理结果",null,null,null);}
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<Result<ErrorData>> duplicate(Exception e,jakarta.servlet.http.HttpServletRequest request){return request.getRequestURI().matches("(?:/api)?/bookshelves(?:/.*)?")?BookCopyActionExceptionHandler.response(409,"LOCATION_CODE_CONFLICT","编号已存在，请重新核对",null,null,null):BookCopyActionExceptionHandler.response(500,"ACTION_SAVE_FAILED","动作保存失败，请先查询原办理结果",null,null,null);}
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Result<ErrorData>> forbidden(Exception e){return BookCopyActionExceptionHandler.response(403,"ACTION_ROLE_REQUIRED","当前角色无权办理或查询逐册管理动作",null,null,null);}
}
