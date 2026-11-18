package com.dking.gateway.filter.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网关限流。三种模式：
 *
 * <pre>
 * enabled=false                     → 直接放行
 * enabled=true,  redis.enabled=false → 只用本地桶
 * enabled=true,  redis.enabled=true  → Redis 全局桶；Redis 故障时降级到本地桶
 * </pre>
 *
 * <h3>降级与恢复的四个约束</h3>
 *
 * <p><b>1. 只对基础设施故障降级。</b>连不上、超时才算 Redis 不可用；
 * Lua 脚本报错、序列化失败属于代码 bug，必须抛出去暴露，降级只会把它藏起来。</p>
 *
 * <p><b>2. 单写者方向。</b>状态只有一个 {@code AtomicBoolean}：
 * 请求线程只能把它从 false 改成 true（open），探测线程只能把它从 true 改成 false（close）。
 * 两个方向各有一个写者，状态机不会被两边同时改乱。</p>
 *
 * <p><b>3. 探测用真实 Lua，只判有没有抛异常。</b>探测打的是一段真实的令牌桶脚本，
 * key 用 {@code __probe__}，覆盖「连接 → 脚本执行 → 写回」整条链路。
 * 返回值（放行/超限）不参与判断——超限说明脚本正常执行了，Redis 是好的。</p>
 *
 * <p><b>4. 连续 N 次成功才切回 + 指数退避。</b>避免 Redis 刚恢复就抖动（半开探测成功一次就全量回切，
 * 紧接着又挂）或者彻底放弃（一直用一个固定间隔猛试）。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String ACTUATOR_PREFIX = "/actuator/";
    /** 探测专用 key，和真实用户桶隔离 */
    private static final String PROBE_KEY = "__probe__";

    private final RateLimitProperties properties;
    private final RedisTokenBucket redisBucket;
    private final LocalTokenBucket localBucket;
    private final JsonMapper json = JsonMapper.builder().build();

    private final Counter degradeCounter;
    private final Counter recoverCounter;
    private final Counter probeSuccessCounter;
    private final Counter probeFailureCounter;
    private final AtomicInteger redisHealthy = new AtomicInteger(1);

    /** 状态唯一真相：请求只能 true，探测线程只能 false */
    private final AtomicBoolean degraded = new AtomicBoolean(false);

    private final AtomicInteger consecutiveProbeSuccess = new AtomicInteger(0);
    private volatile long backoffMillis;

    private final ScheduledExecutorService probeScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "redis-bucket-probe");
                thread.setDaemon(true);
                return thread;
            });

    /** 一次请求该用哪个桶、哪个配额 */
    private record Quota(String key, int limitForPeriod, int capacity) {
    }

    public RateLimitFilter(RateLimitProperties properties,
                           RedisTokenBucket redisBucket,
                           LocalTokenBucket localBucket,
                           MeterRegistry meterRegistry) {
        this.properties = properties;
        this.redisBucket = redisBucket;
        this.localBucket = localBucket;
        this.backoffMillis = baseIntervalMillis();

        this.degradeCounter = Counter.builder("gateway.ratelimit.redis.degrade.total")
                .description("Redis 令牌桶降级次数").register(meterRegistry);
        this.recoverCounter = Counter.builder("gateway.ratelimit.redis.recover.total")
                .description("Redis 令牌桶恢复次数").register(meterRegistry);
        this.probeSuccessCounter = Counter.builder("gateway.ratelimit.redis.probe.success.total")
                .description("Redis 探测成功次数").register(meterRegistry);
        this.probeFailureCounter = Counter.builder("gateway.ratelimit.redis.probe.failure.total")
                .description("Redis 探测失败次数").register(meterRegistry);
        Gauge.builder("gateway.ratelimit.redis.healthy", redisHealthy, value -> (double) value.get())
                .description("Redis 令牌桶是否健康 1/0").register(meterRegistry);

        log.info("限流配置: enabled={}, 用户={}/{}s(cap={}), 匿名={}/{}s(cap={}), redis={}, 探测={}",
                properties.isEnabled(),
                properties.getLimitForPeriod(), properties.getRefillPeriodSeconds(), properties.getCapacity(),
                properties.getAnonymous().getLimitForPeriod(), properties.getRefillPeriodSeconds(),
                properties.getAnonymous().getCapacity(),
                properties.getRedis().isEnabled() ? "ON(" + properties.getRedis().getKeyPrefix() + ")" : "OFF(仅本地桶)",
                probeDescription());
    }

    @PostConstruct
    void startProbeLoop() {
        if (!properties.getRedis().isEnabled()) {
            return;
        }
        if (!properties.getRedis().getProbe().isEnabled()) {
            log.warn("Redis 降级探测已关闭：降级后不会自动恢复，只能重启或手工介入（测试模式）");
            return;
        }
        probeScheduler.schedule(this::tick, baseIntervalMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopProbeLoop() {
        probeScheduler.shutdownNow();
        localBucket.clear();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.isEnabled() || pathOf(request).startsWith(ACTUATOR_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        Quota quota = quotaOf(request);

        if (properties.getRedis().isEnabled()) {
            Boolean allowed = tryRedis(quota, pathOf(request));
            if (allowed != null) {
                if (!allowed) {
                    log.warn("触发限流(redis), key={}, path={}", quota.key(), pathOf(request));
                    tooManyRequests(response);
                    return;
                }
                chain.doFilter(request, response);
                return;
            }
            // null：降级中，交给本地桶
        }

        if (!localBucket.tryAcquire(quota.key(), quota.limitForPeriod(),
                quota.capacity(), properties.getRefillPeriodSeconds())) {
            log.warn("触发限流(local), key={}, path={}", quota.key(), pathOf(request));
            tooManyRequests(response);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * @return true 放行 / false 超限 / null 已降级（调用方走本地桶）
     */
    private Boolean tryRedis(Quota quota, String path) {
        if (!degraded.get()) {
            return callRedis(quota, path);
        }
        // 降级中：请求完全不碰 Redis。恢复只由探测线程负责（单写者）
        return null;
    }

    private Boolean callRedis(Quota quota, String path) {
        try {
            return redisBucket.tryAcquire(quota.key(),
                    quota.limitForPeriod(), quota.capacity(), properties.getRefillPeriodSeconds());
        } catch (RuntimeException e) {
            if (!isInfrastructureFailure(e)) {
                log.error("Redis 令牌桶出现非基础设施异常，不降级，直接失败, path={}", path, e);
                throw e;
            }
            open(e);
            return null;
        }
    }

    /**
     * 探测一次：打真实 Lua，看的是「有没有抛异常」而不是返回值。
     * 返回 false 只表示「这条链路还不通」，不放行/超限都是正常结果。
     */
    private boolean probeOnce() {
        try {
            // 专用 key，配额给最小；返回值不参与判断
            redisBucket.tryAcquire(PROBE_KEY, 1, 1, properties.getRefillPeriodSeconds());
            return true;
        } catch (RuntimeException e) {
            if (!isInfrastructureFailure(e)) {
                log.error("探测遇到非基础设施异常（疑似代码问题），按未恢复处理", e);
            }
            return false;
        }
    }

    /**
     * 探测主循环：自调度，间隔由指数退避决定。
     * 健康时按基础间隔空转（每 5s 一次，成本可忽略），降级时才真正探测。
     */
    private void tick() {
        long delay = baseIntervalMillis();
        try {
            if (degraded.get()) {
                boolean ok = probeOnce();
                if (ok) {
                    probeSuccessCounter.increment();
                    // 已经通了，立刻回到基础间隔，尽快攒够 N 次
                    backoffMillis = baseIntervalMillis();
                    int streak = consecutiveProbeSuccess.incrementAndGet();
                    if (streak >= properties.getRedis().getProbe().getSuccessThreshold()) {
                        close();
                    }
                } else {
                    probeFailureCounter.increment();
                    consecutiveProbeSuccess.set(0);
                    backoffMillis = Math.min(
                            backoffMillis * Math.max(2, properties.getRedis().getProbe().getBackoffMultiplier()),
                            properties.getRedis().getProbe().getMaxIntervalSeconds() * 1000L);
                    delay = backoffMillis;
                    log.debug("Redis 探测失败，下次 {}ms 后重试", delay);
                }
            } else {
                consecutiveProbeSuccess.set(0);
                backoffMillis = baseIntervalMillis();
            }
        } catch (Throwable t) {
            log.warn("探测线程出现未预期异常", t);
        } finally {
            if (!probeScheduler.isShutdown()) {
                probeScheduler.schedule(this::tick, delay, TimeUnit.MILLISECONDS);
            }
        }
    }

    /** 请求线程唯一的写操作：open */
    private void open(RuntimeException e) {
        if (degraded.compareAndSet(false, true)) {
            redisHealthy.set(0);
            degradeCounter.increment();
            consecutiveProbeSuccess.set(0);
            backoffMillis = baseIntervalMillis();
            log.warn("Redis 令牌桶不可用，降级到本地桶（{}），原因: {}",
                    properties.getRedis().getProbe().isEnabled() ? "由探测线程恢复" : "探测已关闭，不会自动恢复",
                    e.toString());
        }
    }

    /** 探测线程唯一的写操作：close */
    private void close() {
        if (degraded.compareAndSet(true, false)) {
            redisHealthy.set(1);
            recoverCounter.increment();
            consecutiveProbeSuccess.set(0);
            backoffMillis = baseIntervalMillis();
            log.info("Redis 令牌桶已恢复（连续 {} 次探测成功），退出降级",
                    properties.getRedis().getProbe().getSuccessThreshold());
        }
    }

    /**
     * 只有「连不上 / 超时」这类资源型异常才算 Redis 不可用。
     * RedisSystemException（例如 Lua 脚本报错）属于代码问题，不能当成降级理由。
     */
    private static boolean isInfrastructureFailure(Throwable e) {
        return e instanceof DataAccessResourceFailureException
                || e instanceof TransientDataAccessException;
    }

    private long baseIntervalMillis() {
        return Math.max(1, properties.getRedis().getProbe().getIntervalSeconds()) * 1000L;
    }

    private String probeDescription() {
        RateLimitProperties.Probe probe = properties.getRedis().getProbe();
        if (!properties.getRedis().isEnabled()) {
            return "N/A";
        }
        if (!probe.isEnabled()) {
            return "OFF(降级不会自动恢复)";
        }
        return "ON(" + probe.getIntervalSeconds() + "s ~ " + probe.getMaxIntervalSeconds()
                + "s, x" + probe.getBackoffMultiplier() + ", 连续 " + probe.getSuccessThreshold() + " 次)";
    }

    private Quota quotaOf(HttpServletRequest request) {
        String userId = request.getHeader(USER_ID_HEADER);
        if (userId != null && !userId.isBlank()) {
            return new Quota("user:" + userId, properties.getLimitForPeriod(), properties.getCapacity());
        }
        return new Quota("ip:" + request.getRemoteAddr(),
                properties.getAnonymous().getLimitForPeriod(),
                properties.getAnonymous().getCapacity());
    }

    private String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() ? uri.substring(context.length()) : uri;
    }

    private void tooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(properties.getRefillPeriodSeconds()));
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 429);
        body.put("message", "请求过于频繁，请稍后再试");
        body.put("data", null);
        response.getWriter().write(json.writeValueAsString(body));
    }
}
