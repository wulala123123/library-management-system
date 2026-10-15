package com.library.controller;

import com.library.common.Result;
import com.library.service.MemberConflictException;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

/** Member SQL errors can contain family data. Keep them out of shared logs and responses. */
@Order(0)
@RestControllerAdvice(assignableTypes = {MemberController.class})
public class MemberExceptionHandler {
    @ExceptionHandler(MemberConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<Void> conflict(MemberConflictException error) { return Result.error(409, error.getMessage()); }

    @ExceptionHandler(DataAccessException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> databaseFailure() { return Result.error(500, "会员资料保存失败，请稍后重试"); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> invalidBody() { return Result.error(400, "会员资料格式错误或包含只读字段"); }
}
