package com.hmdp.rabbitmq;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.config.RabbitMQConfig;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static com.hmdp.config.SeckillDelayMQConfig.SECKILL_DELAY;
import static com.hmdp.config.SeckillDelayMQConfig.SECKILL_DELAY_EXCHANGE;

/**
 * 消息消费者
 */
@Slf4j
@Service
public class MQReceiver {

    @Resource
    IVoucherOrderService voucherOrderService;

    @Resource
    ISeckillVoucherService seckillVoucherService;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    RabbitTemplate rabbitTemplate;

    /**
     * 接收秒杀信息并下单
     * @param voucherOrder
     */
    @Transactional
    @RabbitListener(queues = RabbitMQConfig.QUEUE) /*concurrency = "1-10"*/
    public void receiveSeckillMessage(VoucherOrder voucherOrder, Message message){


        log.info("接收到消息: "+voucherOrder);

        String messageId = message.getMessageProperties().getMessageId();
        String idKey = "MESSAGE_ID:" + messageId;


        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(idKey))) {
                log.warn("消息 {} 已处理过，跳过", messageId);
                return;
            }

            Long voucherId = voucherOrder.getVoucherId();
            //5.一人一单
            Long userId = voucherOrder.getUserId();


            //Lua脚本以保证一人一单了

//            //5.1查询订单
//            int count = voucherOrderService.query().eq("user_id",userId).eq("voucher_id", voucherId).count();
//            //5.2判断是否存在
//            if(count>0){
//                //用户已经购买过了
//                log.error("该用户已购买过");
//                return ;
//            }


            log.info("扣减库存");


            //6.扣减库存
            boolean success = seckillVoucherService
                    .update()
                    .setSql("stock = stock-1")
                    .eq("voucher_id", voucherId)
                    .gt("stock",0)//cas乐观锁
                    .update();
            if(!success){
                log.error("库存不足");
                return;
            }

            //直接保存订单
            voucherOrderService.save(voucherOrder);

           //ZJY TODO 2025/10/10:事务提交后才把事务id放入redis中
            // 2. DB 事务提交成功后，再更新 Redis
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronizationAdapter() {
                        @Override
                        public void afterCommit() {

                            //保证幂等性
                            redisTemplate.opsForValue().set(idKey, "1", 10, TimeUnit.MINUTES);

                            //转发给延时交换机
                            rabbitTemplate.convertAndSend(SECKILL_DELAY_EXCHANGE,SECKILL_DELAY,voucherOrder);
                        }
                    });


        } catch (Exception e) {
            //这里大概率不会出现异常??
            log.error("异常");
        }

    }

    // 处理超时未支付订单（核心优化：添加状态校验）
    @RabbitListener(queues = "seckill.timeout.queue")
    public void handleTimeoutOrder(VoucherOrder voucherOrder, Channel channel, Message message) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        try {
        Long orderId =voucherOrder.getId();
        Long voucherId = voucherOrder.getVoucherId();
        Long userId = voucherOrder.getUserId();


        // 关键步骤：查询订单当前状态
            VoucherOrder order = voucherOrderService.getById(orderId);
            if (order == null) {
                // 订单不存在，直接确认消息
                //channel.basicAck(deliveryTag, false);
                return;
            }

        // 若订单已支付（状态2）或已取消（状态4），则不执行恢复
            if (order.getStatus() != 1) {
                log.info("订单{}已支付或取消，无需处理超时", orderId);
                //channel.basicAck(deliveryTag, false);
                return;
             }

        // 仅未支付订单（状态1）执行库存恢复
            voucherOrderService.recoverStock(orderId, voucherId, userId);
            //channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.error("超时订单处理失败", e);
            //channel.basicNack(deliveryTag, false, false); // 拒绝消息，不重回队列
        }
    }


}