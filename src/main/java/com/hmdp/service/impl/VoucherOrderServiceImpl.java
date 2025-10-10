package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.google.common.util.concurrent.RateLimiter;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.rabbitmq.MQSender;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateConfigurer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    ISeckillVoucherService seckillVoucherService;

    @Autowired
    IVoucherOrderService voucherOrderService;

    @Autowired
    RedisIdWorker redisIdWorker;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> redisScript;

    @Autowired
    private MQSender mqSender;

    //private RateLimiter rateLimiter=RateLimiter.create(10);

    static {
        //静态代码块初始化Script
        redisScript = new DefaultRedisScript<>();

        //设置资源的地址
        redisScript.setLocation(new ClassPathResource("redis2.lua"));

        //设置返回类型
        redisScript.setResultType(Long.class);
    };

    //代理对象
    IVoucherOrderService proxy;


    //创建阻塞队列
    private final BlockingQueue<VoucherOrder> orderTask = new ArrayBlockingQueue(1024*1024);

    //异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //在类初始化后提交任务执行
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    };

    class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    VoucherOrder task = orderTask.take();

                    handleVoucherOrder(task);

                } catch (InterruptedException e) {
                    log.error("处理订单失败");
                }
            }
        }
    }

    public void handleVoucherOrder(VoucherOrder task) {

        RLock lock = redissonClient.getLock("order:" + task.getUserId());


        boolean isLock = lock.tryLock();

        if(!isLock){
            log.error("不允许重复下单");
        };

        try{
            //不能通过以下方法，因为现在是在另一个线程执行
            //IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            proxy.createVoucherOrder(task);
        }finally {
            lock.unlock();
        }
    }



    @Override
    public Result seckillVoucher(Long voucherId) {


        //获取用户
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        //执行lua脚本
        Long r = redisTemplate.execute(
                redisScript,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );

        //判断返回结果
        if(r != 0){
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        };

        //ZJY TODO 2025/9/20:将优惠券id,用户id,和订单id存入阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();

        //2.2 订单id
        voucherOrder.setId(orderId);
        // 2.3.用户id
        voucherOrder.setUserId(userId);
        // 2.4.代金券id
        voucherOrder.setVoucherId(voucherId);

        //ZJY TODO 2025/9/21:一下是用阻塞队列做的我们替换为消息队列
//        //2.6 获取代理对象
        //proxy = (IVoucherOrderService)AopContext.currentProxy();
//
//        //2.5 放入阻塞队列
//        orderTask.add(voucherOrder);

        //ZJY TODO 2025/9/22:发送消息
        mqSender.sendSeckillMessage(voucherOrder);


        //下单成功返回订单id
        return Result.ok(orderId);
    }



//    @Override
//    public Result seckillVoucher(Long voucherId) {
//
//        //查询优惠券信息
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//
//        if(seckillVoucher == null){
//            return Result.fail("优惠券不存在");
//        };
//
//        //判断秒杀是否开始
//        if(LocalDateTime.now().isAfter(seckillVoucher.getEndTime()) ||
//            LocalDateTime.now().isBefore(seckillVoucher.getBeginTime())){
//
//                //不在秒杀区间
//                return Result.fail("秒杀未开始");
//        };
//
//        //判断库存是否充足
//        Integer stock = seckillVoucher.getStock();
//
//        if(stock<1){
//            return Result.fail("库存不足");
//        };
//
//
//        Long userId = UserHolder.getUser().getId();
//
//        //通过加synchronized的方式不能够实现在集群模式下的多线程互斥
//        //改成使用 redis实现的 分布式锁
//
//
////        synchronized (userId.toString().intern()){
////            //获取代理对象
////            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//
//        //这个方法不用了，我们使用redisson
//        //SimpleRedisLock lock = new SimpleRedisLock(redisTemplate,"order:" + userId);
//
//        RLock lock = redissonClient.getLock("order:" + userId);
//
//        //判断是否加锁成功
//        //boolean isLock = lock.tryLock(1200);
//
//        boolean isLock = lock.tryLock();
//
//        if(!isLock){
//            return Result.fail("不允许重复下单");
//        };
//
//        try{
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
//
//            return proxy.createVoucherOrder(voucherId);
//        }finally {
//            lock.unlock();
//        }
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //新增一人一单逻辑

        //根据voucherId和用户id 查看是否存在相关订单
        //Long userId = UserHolder.getUser().getId();

        Long userId = voucherOrder.getUserId();

            int count = query().eq("voucher_id", voucherOrder.getVoucherId()).eq("user_id", userId).count();

            if(count>0){
                log.error("用户已经购买过一次！");
            }

            //5，扣减库存

            boolean success = seckillVoucherService.update()
                    .setSql("stock= stock -1")
                    .eq("voucher_id", voucherOrder.getVoucherId())
                    .gt("stock",0).update(); //where id = ? and stock > 0 update();
            if (!success) {
                //扣减库存
                log.error("库存不足！");
            }
            save(voucherOrder);
        }


    // 初始化Lua脚本

    private static final DefaultRedisScript<Long> RECOVER_SCRIPT;

    static {
        RECOVER_SCRIPT = new DefaultRedisScript<>();
        RECOVER_SCRIPT.setLocation(new ClassPathResource("recover_stock.lua"));
        RECOVER_SCRIPT.setResultType(Long.class);
    }

    /**
     * 库存恢复核心方法
     * @param orderId 订单ID
     * @param voucherId 优惠券ID
     * @param userId 用户ID
     */
    @Transactional
    public void recoverStock(Long orderId, Long voucherId, Long userId) {
        // 1. 数据库层面：更新订单状态为“已取消”+恢复库存（乐观锁保证原子性）
        // 1.1 先更新订单状态（仅未支付订单可取消）

        UpdateWrapper<VoucherOrder> voucherOrderUpdateWrapper = new UpdateWrapper<>();
        voucherOrderUpdateWrapper.set("status",4).eq("id",orderId).eq("status",1);
        boolean update = voucherOrderService.update(voucherOrderUpdateWrapper);


        if (update == false) {
            log.error("订单{}无需恢复(已支付或已取消)",orderId);
            return;
        }

         //1.2 恢复数据库库存

        UpdateWrapper<SeckillVoucher> seckillVoucherUpdateWrapper = new UpdateWrapper<>();
        seckillVoucherUpdateWrapper.setSql("stock = stock + 1").eq("voucher_id",voucherId).eq("stock",seckillVoucherService.getById(voucherId).getStock());

        boolean recoverDb = seckillVoucherService.update(seckillVoucherUpdateWrapper);

        if (!recoverDb) {
            log.error("订单{}数据库库存恢复失败", orderId);
            return;
        }

        // 2. Redis层面：执行Lua脚本恢复库存
        Long redisResult = redisTemplate.execute(
        RECOVER_SCRIPT,
                Collections.emptyList(),
                orderId.toString(),
                voucherId.toString(),
                userId.toString()
        );

        if (redisResult == 1) {
            log.info("订单{} 库存恢复成功 (DB+Redis) ",orderId);
        } else {
            log.info("订单{} Redis库存已恢复，无需重复操作",orderId);

        }
    }


}
