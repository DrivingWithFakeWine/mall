package com.dking.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * DoroShop 统一网关。
 *
 * <p>用 WebMVC 版而不是 WebFlux 版：后端是 Servlet 栈，网关也用 MVC 只需维护一套编程模型，
 * 过滤器、异常处理、鉴权都能复用同一套写法。</p>
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
