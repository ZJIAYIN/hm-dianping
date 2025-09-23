package com.hmdp.config;

import com.hmdp.entity.VoucherOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;


@Configuration
@RequiredArgsConstructor
public class RabbitMQConfig {


    //这里是消费者正常监听时的那套
    public static final String QUEUE = "seckillQueue";
    public static final String EXCHANGE = "seckillExchange";
    public static final String ROUTINGKEY = "seckill";

    //这里是消费者reject逻辑下的转发至其他交换机
    public static final String ERROR_QUEUE = "errorQueue";
    public static final String ERROR_EXCHANGE = "errorExchange";
    public static final String ERROR_ROUTINGKEY = "error";
    private static final Log log = LogFactory.getLog(RabbitMQConfig.class);


    //这里是消费者正常监听时的那套
    @Bean
    public Queue queue(){
        return QueueBuilder.durable(QUEUE).lazy().build();
    }
    @Bean
    public DirectExchange Exchange(){
        return new DirectExchange(EXCHANGE);
    }
    @Bean
    public Binding binding(){
        return BindingBuilder.bind(queue()).to(Exchange()).with(ROUTINGKEY);
    }


//    //这里是消费者reject逻辑下的转发至其他交换机
//    @Bean
//    public Queue queue2(){
//        return QueueBuilder.durable(ERROR_QUEUE).lazy().build();
//    }
//    @Bean
//    public DirectExchange Exchange2(){
//        return new DirectExchange(ERROR_EXCHANGE);
//    }
//    @Bean
//    public Binding binding2(){
//        return BindingBuilder.bind(queue2()).to(Exchange2()).with(ERROR_ROUTINGKEY);
//    }
//
//    //当到达最大重试后进入其他交换机
//    @Bean
//    public MessageRecoverer republishMessageRecoverer(RabbitTemplate rabbitTemplate){
//        return new RepublishMessageRecoverer(rabbitTemplate, ERROR_EXCHANGE, ERROR_ROUTINGKEY);
//    }

//    @RabbitListener(queues = RabbitMQConfig.ERROR_QUEUE)
//    public void errorSolver(VoucherOrder voucherOrder){
//        log.error(voucherOrder+"接收失败");
//    };

    @Bean
    public MessageConverter messageConverter(){
        // 1.定义消息转换器
        Jackson2JsonMessageConverter jjmc = new Jackson2JsonMessageConverter();
        // 2.配置自动创建消息id，用于识别不同消息，也可以在业务中基于ID判断是否是重复消息
        jjmc.setCreateMessageIds(true);
        return jjmc;
    }

}
