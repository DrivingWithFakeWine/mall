package com.dking.gateway.filter.circuitbreaker;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关熔断 + 降级。
 *
 * <p><b>为什么熔断和降级必须配套：</b>熔断只是「不调用下游」，如果不给调用方一个明确的回应，
 * 请求要么挂在那里等超时、要么抛 500。降级就是熔断打开时的那个回应——快速失败 + 可读的错误 +
 * Retry-After，让前端知道「等一会儿再来」，而不是以为接口坏了。</p>
 *
 * <p><b>只统计 5xx：</b>4xx 是调用方的问题（参数错、没登录），不该算到下游服务头上，
 * 否则一次批量扫接口就能把服务「熔断」掉。</p>
 *
 * <p><b>放在限流之后：</b>限流保护网关自己，熔断保护下游；被限流拦掉的请求不该污染熔断统计。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class CircuitBreakerFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerFilter.class);

    private final CircuitBreakerProperties properties;
    private final CircuitBreakerRegistry registry;
    private final JsonMapper json = JsonMapper.builder().build();

    public CircuitBreakerFilter(CircuitBreakerProperties properties, CircuitBreakerRegistry registry) {
        this.properties = properties;
        this.registry = registry;
        log.info("熔断配置: enabled={}, 阈值={}次/{}s, 打开={}s, 半开放行={}",
                properties.isEnabled(), properties.getFailureThreshold(),
                properties.getWindowSeconds(), properties.getOpenSeconds(),
                properties.getHalfOpenPermits());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String serviceKey = serviceKeyOf(request);
        if (serviceKey == null) {
            chain.doFilter(request, response);
            return;
        }

        CircuitBreakerRegistry.Breaker breaker = registry.breaker(serviceKey);
        if (!breaker.tryPass()) {
            log.warn("熔断打开，直接降级, service={}, path={}", serviceKey, pathOf(request));
            degrade(response, serviceKey);
            return;
        }

        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = response.getStatus() >= 500;
        } finally {
            if (failed) {
                breaker.recordFailure();
            } else {
                breaker.recordSuccess();
            }
        }
    }

    /**
     * 服务 key = 路径第一段。和网关路由的粒度一致：
     * {@code /product/**} → {@code product}，{@code /order/**} → {@code order}。
     *
     * @return null 表示这条路径不参与熔断（健康检查等）
     */
    private String serviceKeyOf(HttpServletRequest request) {
        String path = pathOf(request);
        if (path == null || path.length() < 2) {
            return null;
        }
        int nextSlash = path.indexOf('/', 1);
        String key = nextSlash < 0 ? path.substring(1) : path.substring(1, nextSlash);
        return properties.getExclude().contains(key) ? null : key;
    }

    /**
     * 降级响应：快速失败，不占用下游连接。
     *
     * <p>这里返回的是「可读的失败」，不是 500。更进一步的做法是返回缓存快照
     * （比如商品列表的上一版数据），但那要先有缓存，属于下一阶段。</p>
     */
    private void degrade(HttpServletResponse response, String serviceKey) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader("Retry-After", String.valueOf(properties.getOpenSeconds()));
        response.setHeader("X-Circuit-Breaker", "OPEN");
        response.setHeader("X-Circuit-Service", serviceKey);
        response.setContentType("application/json;charset=UTF-8");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 503);
        body.put("message", "服务 [" + serviceKey + "] 暂时不可用，请稍后再试");
        body.put("data", null);
        response.getWriter().write(json.writeValueAsString(body));
    }

    private String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() ? uri.substring(context.length()) : uri;
    }
}
