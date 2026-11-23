package com.dking.marketing;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 营销服务：秒杀活动、库存闸门、库存桶、活动缓存、下单消息发布。
 *
 * <p>不直接写订单表——建单通过 MQ 交给交易服务（Shop），
 * 两个域之间只有消息，没有同步调用。</p>
 */
@SpringBootApplication
@MapperScan("com.dking.marketing.mapper")
public class MarketingApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketingApplication.class, args);
    }
}
