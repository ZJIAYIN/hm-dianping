package com.hmdp.rabbitmq;

import com.hmdp.config.RabbitMQConfig;
import com.hmdp.entity.VoucherOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.hmdp.config.SeckillDelayMQConfig.SECKILL_DELAY;
import static com.hmdp.config.SeckillDelayMQConfig.SECKILL_DELAY_EXCHANGE;

@Service
@Slf4j
public class MQSender {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private static final String ROUTINGKEY = "seckill";

    /**
     * 发生秒杀信息
     * @param voucherOrder
     */
    public void sendSeckillMessage(VoucherOrder voucherOrder){
        log.info("发送消息"+voucherOrder);
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE,ROUTINGKEY,voucherOrder);


    }

}
