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

        //ZJY TODO 2025/10/8:使用Zset + 时间窗口思想 进行限流
        // 1. 判断是否在一级限制条件内
        Boolean oneLevelLimit = redisTemplate.opsForSet().isMember(ONE_LEVERLIMIT_KEY + phone, "1");
        if (oneLevelLimit != null && oneLevelLimit) {
            // 在一级限制条件内，不能发送验证码
            return Result.fail("您需要等5分钟后再请求");
        }

// 2. 判断是否在二级限制条件内
        Boolean twoLevelLimit = redisTemplate.opsForSet().isMember(TWO_LEVERLIMIT_KEY + phone, "1");
        if (twoLevelLimit != null && twoLevelLimit) {
            // 在二级限制条件内，不能发送验证码
            return Result.fail("您需要等20分钟后再请求");
        }

// 3. 检查过去1分钟内发送验证码的次数
        long oneMinuteAgo = System.currentTimeMillis() - 60 * 1000;
        long count_oneminute = redisTemplate.opsForZSet().count(SENDCODE_SENDTIME_KEY + phone, oneMinuteAgo, System.currentTimeMillis());
        if (count_oneminute >= 1) {
            // 过去1分钟内已经发送了1次，不能再发送验证码
            return Result.fail("距离上次发送时间不足1分钟，请1分钟后重试");
        }

        // 4. 检查发送验证码的次数
        long fiveMinutesAgo = System.currentTimeMillis() - 5 * 60 * 1000;
        long count_fiveminute = redisTemplate.opsForZSet().count(SENDCODE_SENDTIME_KEY + phone, fiveMinutesAgo, System.currentTimeMillis());
        if (count_fiveminute % 3 == 2 && count_fiveminute > 5) {
            // 发送了8, 11, 14, ...次，进入二级限制
            redisTemplate.opsForSet().add(TWO_LEVERLIMIT_KEY + phone, "1");
            redisTemplate.expire(TWO_LEVERLIMIT_KEY + phone, 20, TimeUnit.MINUTES);
            return Result.fail("接下来如需再发送，请等20分钟后再请求");
        } else if (count_fiveminute == 5) {
            // 过去5分钟内已经发送了5次，进入一级限制
            redisTemplate.opsForSet().add(ONE_LEVERLIMIT_KEY + phone, "1");
            redisTemplate.expire(ONE_LEVERLIMIT_KEY + phone, 5, TimeUnit.MINUTES);
            return Result.fail("5分钟内已经发送了5次，接下来如需再发送请等待5分钟后重试");
        }

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

        // 更新发送时间和次数
        redisTemplate.opsForZSet().add(SENDCODE_SENDTIME_KEY + phone, System.currentTimeMillis() + "", System.currentTimeMillis());

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
