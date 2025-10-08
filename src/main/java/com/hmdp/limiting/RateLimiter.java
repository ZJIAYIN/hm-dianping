package com.hmdp.limiting;

import com.hmdp.limiting.LimitType;

import java.lang.annotation.*;

/**
 * 限流注解
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimiter {

    /**
     * 限流 key，支持 Spring EL 表达式
     * 示例： #id、#user.id、#mobile
     */
    String key() default "";

    /**
     * 限流时间窗口（秒）
     */
    int time() default 60;

    /**
     * 时间窗口内允许的请求数
     */
    int count() default 100;

    /**
     * 限流类型（全局 / 按 IP）
     */
    LimitType limitType() default LimitType.DEFAULT;

    /**
     * 提示消息
     */
    String message() default "请求过于频繁，请稍后重试";
}
