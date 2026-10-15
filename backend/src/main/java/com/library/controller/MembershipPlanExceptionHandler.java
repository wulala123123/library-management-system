package com.library.controller;

import com.library.common.Result;
import com.library.service.MembershipPlanConflictException;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@Order(0)
@RestControllerAdvice(assignableTypes = MembershipPlanController.class)
public class MembershipPlanExceptionHandler {
    @ExceptionHandler(MembershipPlanConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> conflict(MembershipPlanConflictException error) { return Result.error(409, error.getMessage()); }
    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> database() { return Result.error(500, "套餐资料保存失败，请稍后重试"); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> malformed() { return Result.error(400, "套餐资料格式错误或包含不支持的字段"); }
}
