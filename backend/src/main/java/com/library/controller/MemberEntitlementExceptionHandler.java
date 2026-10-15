package com.library.controller;

import com.library.common.Result;
import com.library.service.MemberConflictException;
import com.library.service.MemberEntitlementConflictException;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@Order(0)
@RestControllerAdvice(assignableTypes = MemberEntitlementController.class)
public class MemberEntitlementExceptionHandler {
    @ExceptionHandler({MemberEntitlementConflictException.class, MemberConflictException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> conflict(RuntimeException error) { return Result.error(409, error.getMessage()); }
    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> database() { return Result.error(500, "权益资料保存失败，请稍后重试"); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> invalid() { return Result.error(400, "权益资料格式错误，实际权益字段须明确提供"); }
}
