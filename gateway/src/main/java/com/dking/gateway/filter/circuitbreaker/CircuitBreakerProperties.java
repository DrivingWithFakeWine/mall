package com.dking.gateway.filter.circuitbreaker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 熔断器配置。
 *
 * <p>参数含义：{@code window-seconds} 内累计 {@code failure-threshold} 次失败就打开；
 * 打开后 {@code open-seconds} 秒进入半开，放一个探测请求过去，成功则关闭，失败则重新打开。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.circuit-breaker")
public class CircuitBreakerProperties {

    /** 总开关：false = 不做熔断（只保留限流和鉴权） */
    private boolean enabled = true;

    /** 窗口内失败多少次打开熔断 */
    private int failureThreshold = 5;

    /** 统计窗口长度（秒） */
    private int windowSeconds = 10;

    /** 打开后多久进入半开（秒） */
    private int openSeconds = 20;

    /** 半开时允许同时通过的探测请求数，一般 1 就够 */
    private int halfOpenPermits = 1;

    /** 不参与熔断的路径第一段（健康检查等） */
    private Set<String> exclude = new LinkedHashSet<>(Set.of("actuator"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public int getOpenSeconds() {
        return openSeconds;
    }

    public void setOpenSeconds(int openSeconds) {
        this.openSeconds = openSeconds;
    }

    public int getHalfOpenPermits() {
        return halfOpenPermits;
    }

    public void setHalfOpenPermits(int halfOpenPermits) {
        this.halfOpenPermits = halfOpenPermits;
    }

    public Set<String> getExclude() {
        return exclude;
    }

    public void setExclude(Set<String> exclude) {
        this.exclude = exclude;
    }
}
