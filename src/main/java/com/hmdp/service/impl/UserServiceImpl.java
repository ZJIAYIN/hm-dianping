package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // TODO 发送短信验证码并保存验证码

        //1.校验手机号是否符合
        if(RegexUtils.isPhoneInvalid(phone)){
            //不符合之间返回
            return Result.fail("无效手机号");
        };


        //符合 生成验证码
        String randomNumbers = RandomUtil.randomNumbers(6);

//        //2.把验证码存到session里面
//        session.setAttribute("code",randomNumbers);
//        session.setAttribute("phone",phone);

        //把验证码存入到redis里
        redisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,randomNumbers);
        redisTemplate.expire(LOGIN_CODE_KEY+phone, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //3.发送验证码
        log.info("验证码{}",randomNumbers);

        //return Result.fail("功能未完成");
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // TODO 实现登录功能

        //手机号格式的检验
        if(RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            return Result.fail("手机号格式错误");
        };

        //验证

//        if(     session.getAttribute("phone") == null ||
//                !session.getAttribute("phone").equals(loginForm.getPhone()) ||
//                session.getAttribute("code") == null ||
//                !session.getAttribute("code").equals(loginForm.getCode())){
//            return Result.fail("手机号或验证码错误");
//        };

        //检验手机号和验证码
        String code = (String)redisTemplate.opsForValue().get(LOGIN_CODE_KEY + loginForm.getPhone());

        if(code == null || !code.equals(loginForm.getCode())){
            return Result.fail("手机号或验证码错误");
        };

        //查询用户是否存在
        User user = query().eq("phone", loginForm.getPhone()).one();

        if(user == null){
            //创建用户
            user = createUserWithPhone(loginForm.getPhone());
        };

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //session.setAttribute("user",userDTO);

        //把用户保存到redis里面

        //1.生成随机token
        String token = UUID.randomUUID().toString();
        String tokenKey = LOGIN_USER_KEY + token;

        //2.以hash形式存储
        Map<String,String> map = new HashMap<>();
        map.put("id",userDTO.getId().toString());
        map.put("nickName",userDTO.getNickName());
        map.put("icon",userDTO.getIcon());
        redisTemplate.opsForHash().putAll(tokenKey,map);

        //3.设置token的有效期
        redisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        return Result.ok(tokenKey);
    }

    User createUserWithPhone(String phone){

        User user = new User();
        user.setPhone(phone);
        user.setNickName(RandomUtil.randomString(10));

        //保存用户
        save(user);

        return null;

    };
}
