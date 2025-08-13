package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshInterceptor implements HandlerInterceptor {


    private StringRedisTemplate redisTemplate;

    public RefreshInterceptor(StringRedisTemplate redisTemplate){
        this.redisTemplate = redisTemplate;
    };

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        //1.获取token
        String tokenKey = request.getHeader("authorization");
        if(StrUtil.isBlank(tokenKey)){
            //没登录
            return true;
        };

        //2.查询Redis的用户
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(tokenKey);

        //3.保存到ThreadLocal(没有的话在第二个拦截器那里拦截)
        UserDTO userDTO = BeanUtil.fillBeanWithMap(entries,new UserDTO(),false);
        UserHolder.saveUser(userDTO);

        //4.刷新token有效期
        redisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        //5.放行
        return true;

    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
