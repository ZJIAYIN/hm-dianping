package com.hmdp.config;

import com.hmdp.dto.Result;
import com.hmdp.exception.RateLimitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }

    @ExceptionHandler(RateLimitException.class)
    public Result handleRateLimitException(RateLimitException e) {
        log.error(e.toString(), e);
        return Result.fail("访问过于频繁");
    }

}
