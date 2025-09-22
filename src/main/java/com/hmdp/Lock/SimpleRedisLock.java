package com.hmdp.Lock;

import cn.hutool.core.lang.UUID;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private static final String KEY_PREFIX = RedisConstants.REDIS_LOCK_KEY;

    private StringRedisTemplate stringRedisTemplate;

    private String name;

    private static final DefaultRedisScript<Long> redisScript;

    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    static {
        //静态代码块初始化Script
        redisScript = new DefaultRedisScript<>();

        //设置资源的地址
        redisScript.setLocation(new ClassPathResource("redis.lua"));

        //设置返回类型
        redisScript.setResultType(Long.class);
    };

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {

        //获取线程
        String threadId = ID_PREFIX + Thread.currentThread().getId();


        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);

    }

    @Override
    public void unLock() {

        //1.删除锁的时候我们要判断当前删除的锁是否是自己加的锁，所以要有一个if判断
        //2.即使有了if也不一定正确，因为 获取锁 、判断 、解锁 这三个过程不一定是原子性的
        //3.所以我们采取lua脚本

        //获取线程(这里一开始想用线程id作为唯一标识，thread-id是jvm内部围护的，但是在集群模式下可能重复)
        //long threadId = Thread.currentThread().getId();

        String threadId = ID_PREFIX + Thread.currentThread().getId();

        stringRedisTemplate.execute(redisScript, Collections.singletonList(KEY_PREFIX + name),threadId);


    }

//    @Override
//    public void unLock() {
//        //通过del删除锁
//        stringRedisTemplate.delete(KEY_PREFIX + name);
//    }
}
