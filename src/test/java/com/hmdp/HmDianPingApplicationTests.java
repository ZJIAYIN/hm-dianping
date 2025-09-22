package com.hmdp;

import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.*;

@SpringBootTest
@Slf4j
class HmDianPingApplicationTests {

    @Autowired
    IShopService shopService;

    @Autowired
    RedisIdWorker redisIdWorker;

    @Resource
    RedissonClient redissonClient;

    @Resource
    RedissonClient redissonClient2;

    @Resource
    RedissonClient redissonClient3;

    RLock lock;



    @BeforeEach
    void setUp(){
        RLock lock1 = redissonClient.getLock("lock");
        RLock lock2 = redissonClient2.getLock("lock");
        RLock lock3 = redissonClient3.getLock("lock");

        lock = redissonClient.getMultiLock(lock1,lock2,lock3);

    };

    @Test
    void testSaveShop() throws InterruptedException {
        shopService.saveShop2Redis(1L,10L);
    };

    @Test
    void testIdWorker() throws InterruptedException {

        BlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>(3000);

        ThreadPoolExecutor es = new ThreadPoolExecutor(
                4, 10, 0,
                TimeUnit.SECONDS, workQueue
        );

        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }

        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }


    @Test
    void method1(){

        boolean tryLock = lock.tryLock();

        if(!tryLock){
            log.error("锁获取失败");
            return;
        };

        try{
            log.error("锁获取成功 1");
            method2();
        }finally {
            log.error("锁释放 1");
            lock.unlock();
        }

    };

    void method2(){

        boolean tryLock = lock.tryLock();

        if(!tryLock){
            log.error("锁获取失败");
            return;
        };

        try{
            log.error("锁获取成功 2");
        }finally {
            log.error("锁释放 2");
            lock.unlock();
        }

    };

}
