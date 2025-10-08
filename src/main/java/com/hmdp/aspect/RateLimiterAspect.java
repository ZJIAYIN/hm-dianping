package com.hmdp.aspect;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.servlet.ServletUtil;
import com.hmdp.exception.RateLimitException;
import com.hmdp.limiting.LimitType;
import com.hmdp.limiting.RateLimiter;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.ParserContext;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;

/**
 * 限流切面
 */
@Aspect
@Component
public class RateLimiterAspect {

    @Autowired
    private RedissonClient redissonClient;

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParserContext parserContext = new TemplateParserContext();
    private final DefaultParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    @Before("@annotation(rateLimiter)")
    public void doBefore(JoinPoint joinPoint, RateLimiter rateLimiter) {
        String key = buildRateLimitKey(joinPoint, rateLimiter);

        RRateLimiter limiter = redissonClient.getRateLimiter(key);
        // 设置速率限制（每time秒最多count个请求）
        limiter.trySetRate(RateType.OVERALL, rateLimiter.count(), rateLimiter.time(), RateIntervalUnit.SECONDS);

        // 尝试获取令牌
        if (!limiter.tryAcquire()) {
            throw new RateLimitException(rateLimiter.message());
        }
    }

    /**
     * 生成限流 key
     */
    private String buildRateLimitKey(JoinPoint joinPoint, RateLimiter rateLimiter) {
        String key = rateLimiter.key();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 解析 SpEL 表达式（如果包含 #）
        if (StrUtil.contains(key, "#")) {
            Object[] args = joinPoint.getArgs();
            String[] paramNames = nameDiscoverer.getParameterNames(method);

            if (ArrayUtil.isNotEmpty(paramNames)) {
                StandardEvaluationContext context = new StandardEvaluationContext();
                for (int i = 0; i < paramNames.length; i++) {
                    context.setVariable(paramNames[i], args[i]);
                }

                Expression expression = parser.parseExpression(key, parserContext);
                Object value = expression.getValue(context);
                key = value == null ? "" : value.toString();
            }
        }

        StringBuilder fullKey = new StringBuilder("rate_limit:");

        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attrs.getRequest();

        fullKey.append(request.getRequestURI()).append(":");

        if (rateLimiter.limitType() == LimitType.IP) {
            String ip = ServletUtil.getClientIP(request);
            fullKey.append(ip).append(":");
        }

        fullKey.append(key);
        return fullKey.toString();
    }
}
