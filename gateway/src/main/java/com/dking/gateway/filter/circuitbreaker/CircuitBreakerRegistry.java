package com.dking.gateway.filter.circuitbreaker;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 熔断器注册表：每个「服务」一个熔断器。
 *
 * <p>服务 key 取路径第一段（{@code /product/**} → {@code product}），
 * 与网关路由一一对应，所以熔断粒度就是「服务」而不是「接口」。</p>
 *
 * <p>状态机只有三态：</p>
 * <pre>
 * CLOSED    正常放行；窗口内失败达到阈值 → OPEN
 * OPEN      直接降级，不碰下游；open-seconds 后 → HALF_OPEN
 * HALF_OPEN 只放一个探测请求；成功 → CLOSED，失败 → OPEN
 * </pre>
 *
 * <p><b>指标：</b>{@code gateway.circuitbreaker.state{service}}（0=CLOSED 1=HALF_OPEN 2=OPEN）、
 * {@code gateway.circuitbreaker.open.total{service}}（打开次数）。
 * 在 {@code /actuator/metrics/gateway.circuitbreaker.state} 可以看到。</p>
 */
@Component
public class CircuitBreakerRegistry {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerRegistry.class);

    private final CircuitBreakerProperties properties;
    private final MeterRegistry meterRegistry;
    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreakerRegistry(CircuitBreakerProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public Breaker breaker(String key) {
        return breakers.computeIfAbsent(key, k -> {
            Breaker breaker = new Breaker(k, properties, meterRegistry);
            Gauge.builder("gateway.circuitbreaker.state", breaker, b -> (double) b.stateValue())
                    .description("熔断器状态 0=CLOSED 1=HALF_OPEN 2=OPEN")
                    .tag("service", k)
                    .register(meterRegistry);
            return breaker;
        });
    }

    public Map<String, String> snapshot() {
        Map<String, String> result = new LinkedHashMap<>();
        breakers.forEach((key, breaker) -> result.put(key, breaker.stateName()));
        return result;
    }

    public static final class Breaker {

        private enum State {CLOSED, OPEN, HALF_OPEN}

        private final String key;
        private final CircuitBreakerProperties properties;
        private final Counter openCounter;

        private State state = State.CLOSED;
        private int failuresInWindow;
        private long windowStartAt;
        private long openedAt;
        private int probesInFlight;

        private Breaker(String key, CircuitBreakerProperties properties, MeterRegistry meterRegistry) {
            this.key = key;
            this.properties = properties;
            this.windowStartAt = System.currentTimeMillis();
            this.openCounter = Counter.builder("gateway.circuitbreaker.open.total")
                    .description("熔断器打开次数")
                    .tag("service", key)
                    .register(meterRegistry);
        }

        /**
         * 是否放行。
         *
         * @return false 表示熔断打开，调用方应直接返回降级响应
         */
        public synchronized boolean tryPass() {
            long now = System.currentTimeMillis();
            if (state == State.OPEN) {
                if (now - openedAt >= properties.getOpenSeconds() * 1000L) {
                    state = State.HALF_OPEN;
                    probesInFlight = 0;
                    log.info("熔断器进入半开 key={}", key);
                } else {
                    return false;
                }
            }
            if (state == State.HALF_OPEN) {
                if (probesInFlight >= properties.getHalfOpenPermits()) {
                    return false;
                }
                probesInFlight++;
            }
            return true;
        }

        public synchronized void recordSuccess() {
            probesInFlight = Math.max(0, probesInFlight - 1);
            if (state != State.CLOSED) {
                log.info("熔断器恢复关闭 key={}", key);
            }
            state = State.CLOSED;
            failuresInWindow = 0;
            windowStartAt = System.currentTimeMillis();
        }

        public synchronized void recordFailure() {
            probesInFlight = Math.max(0, probesInFlight - 1);
            long now = System.currentTimeMillis();

            if (state == State.HALF_OPEN) {
                // 探测失败：立刻重新打开，重新计时
                open(now, "半开探测失败");
                return;
            }
            if (state == State.OPEN) {
                return;
            }

            // 窗口过期就重新计数
            if (now - windowStartAt > properties.getWindowSeconds() * 1000L) {
                windowStartAt = now;
                failuresInWindow = 0;
            }
            failuresInWindow++;
            if (failuresInWindow >= properties.getFailureThreshold()) {
                open(now, "窗口内失败 " + failuresInWindow + " 次");
            }
        }

        private void open(long now, String reason) {
            state = State.OPEN;
            openedAt = now;
            failuresInWindow = 0;
            probesInFlight = 0;
            openCounter.increment();
            log.warn("熔断器打开 key={}, 原因={}, {}s 后进入半开",
                    key, reason, properties.getOpenSeconds());
        }

        public synchronized String stateName() {
            return state.name();
        }

        /** 0=CLOSED 1=HALF_OPEN 2=OPEN，给指标用 */
        public synchronized int stateValue() {
            return switch (state) {
                case CLOSED -> 0;
                case HALF_OPEN -> 1;
                case OPEN -> 2;
            };
        }

        public synchronized boolean isOpen() {
            return state == State.OPEN;
        }
    }
}
