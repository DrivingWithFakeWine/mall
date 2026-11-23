package com.dking.marketing.client;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 开启 Feign，只扫描 client 包。
 *
 * <p><b>为什么排除 test profile：</b>Spring Cloud 会把每个 {@code @FeignClient} 注册成 primary bean，
 * 测试里的 {@link com.dking.marketing.client.LocalProductClient} 如果也标 primary，
 * 容器会报「more than one primary bean」。所以测试环境干脆不启用 Feign，
 * 只留本地实现——保证同一时刻只有一个 {@code ProductClient}。</p>
 */
@Configuration
@Profile("!test")
@EnableFeignClients(basePackages = "com.dking.marketing.client")
public class ProductClientConfig {
}
