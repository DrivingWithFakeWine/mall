package com.dking.shop;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 只有一个目的：保证 Spring 上下文能起来。
 *
 * <p>用和其它集成测试一样的 profile：local 提供数据库口令，test 关掉 MQ 监听器自启，
 * 否则跑一次测试就得先起一个 RabbitMQ。</p>
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class ShopApplicationTests {

    @Test
    void contextLoads() {
    }

}
