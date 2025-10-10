package com.hmdp.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SeckillDelayMQConfig {

    //延迟交换机
    public static final String SECKILL_DELAY_EXCHANGE = "seckill.delay.exchange";
    public static final String SECKILL_DELAY_QUEUE = "seckill.delay.queue";
    public static final String SECKILL_DELAY = "seckill.delay";

    //死信交换机
    public static final String SECKILL_DLX_EXCHANGE = "seckill.dlx.exchange";
    public static final String SECKILL_TIMEOUT_QUEUE = "seckill.timeout.queue";
    public static final String SECKILL_TIMEOUT = "seckill.timeout";

    // 延迟交换机
    @Bean
    public DirectExchange seckillDelayExchange() {
        return ExchangeBuilder.directExchange(SECKILL_DELAY_EXCHANGE)
                .durable(true)
                .build();
    }

    // 延迟队列（30分钟超时）
    @Bean
    public Queue seckillDelayQueue() {
        return QueueBuilder.durable(SECKILL_DELAY_QUEUE)
                .withArgument("x-message-ttl", 1 * 60 * 1000) // 2分钟TTL
                .withArgument("x-dead-letter-exchange", SECKILL_DLX_EXCHANGE) // 死信交换机
                .withArgument("x-dead-letter-routing-key",SECKILL_TIMEOUT ) // 死信路由键
                .build();
    }

    // 绑定延迟交换机和延迟队列
    @Bean
    public Binding delayBinding() {
        return BindingBuilder.bind(seckillDelayQueue())
                .to(seckillDelayExchange())
                .with(SECKILL_DELAY);
    }

    // 死信交换机（接收超时消息）
    @Bean
    public DirectExchange seckillDlxExchange() {
        return ExchangeBuilder.directExchange(SECKILL_DLX_EXCHANGE)
                .durable(true)
                .build();
    }

    // 超时处理队列（最终消费超时订单）
    @Bean
    public Queue seckillTimeoutQueue() {
        return QueueBuilder.durable(SECKILL_TIMEOUT_QUEUE).build();
    }

    // 绑定死信交换机和超时处理队列
    @Bean
    public Binding dlxBinding() {
        return BindingBuilder.bind(seckillTimeoutQueue())
        .to(seckillDlxExchange())
        .with(SECKILL_TIMEOUT);
    }

}
