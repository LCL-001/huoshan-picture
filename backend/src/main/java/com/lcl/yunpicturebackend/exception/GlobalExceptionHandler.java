package com.lcl.yunpicturebackend.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import com.lcl.yunpicturebackend.common.BaseResponse;
import com.lcl.yunpicturebackend.common.ResultUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(NotLoginException.class)
    public BaseResponse<?> notLoginException(NotLoginException e) {
        // 未登录属于常见业务场景，降级为 warn 避免日志噪音
        log.warn("NotLoginException: {}", e.getMessage());
        return ResultUtils.error(ErrorCode.NOT_LOGIN_ERROR, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public BaseResponse<?> maxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("MaxUploadSizeExceededException: {}", e.getMessage());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "上传文件过大");
    }

    @ExceptionHandler(NotPermissionException.class)
    public BaseResponse<?> notPermissionExceptionHandler(NotPermissionException e) {
        log.error("NotPermissionException", e);
        return ResultUtils.error(ErrorCode.NO_AUTH_ERROR, e.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        log.error("BusinessException", e);
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }
}

