package com.dking.gateway.filter.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 限流配置。
 *
 * <p><b>为什么登录和匿名要两套配额：</b>登录用户按 userId 计数，一个 IP 后面有几百个用户也互不影响；
 * 匿名用户只能按 IP 计数，而一个 NAT 出口（校园网、公司网关）后面可能有大量用户，
 * 所以 IP 配额必须显著大于单用户配额，否则会集体误伤。</p>
 *
 * <p>算法是令牌桶：桶容量 capacity，每 refill-period-seconds 秒补满 limit-for-period 个令牌。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    /** 总开关：true 开启限流，false 直接放行 */
    private boolean enabled = true;

    /** 已登录用户：每个周期允许的请求数（按 userId 计数） */
    private int limitForPeriod = 20;

    /** 周期长度（秒） */
    private int refillPeriodSeconds = 60;

    /** 已登录用户：桶容量（瞬时突发上限） */
    private int capacity = 20;

    /** 匿名请求：按 IP 计数的配额 */
    private Anonymous anonymous = new Anonymous();

    /** Redis 令牌桶：关闭则只用本地桶 */
    private Redis redis = new Redis();

    public static class Anonymous {

        /** 匿名：每个周期允许的请求数（按 IP 计数），要比单用户配额大得多 */
        private int limitForPeriod = 200;

        /** 匿名：桶容量 */
        private int capacity = 200;

        public int getLimitForPeriod() {
            return limitForPeriod;
        }

        public void setLimitForPeriod(int limitForPeriod) {
            this.limitForPeriod = limitForPeriod;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }
    }

    public static class Redis {

        /** Redis 令牌桶开关：true = 全局配额（多实例共享）；false = 仅本地桶 */
        private boolean enabled = true;

        /** key 前缀，便于在 Redis 里区分业务 */
        private String keyPrefix = "doro:rate";

        /** 降级探测：用真实 Lua 打 __probe__ key，判断 Redis 是否恢复 */
        private Probe probe = new Probe();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public Probe getProbe() {
            return probe;
        }

        public void setProbe(Probe probe) {
            this.probe = probe;
        }
    }

    public static class Probe {

        /**
         * 探测开关。关闭后 Redis 降级不会自动恢复（保持本地桶），仅用于测试/排查。
         */
        private boolean enabled = true;

        /** 初始探测间隔（秒） */
        private int intervalSeconds = 5;

        /** 退避上限（秒） */
        private int maxIntervalSeconds = 60;

        /** 每次失败后间隔翻几倍 */
        private int backoffMultiplier = 2;

        /** 连续成功多少次才切回 Redis */
        private int successThreshold = 3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getIntervalSeconds() {
            return intervalSeconds;
        }

        public void setIntervalSeconds(int intervalSeconds) {
            this.intervalSeconds = intervalSeconds;
        }

        public int getMaxIntervalSeconds() {
            return maxIntervalSeconds;
        }

        public void setMaxIntervalSeconds(int maxIntervalSeconds) {
            this.maxIntervalSeconds = maxIntervalSeconds;
        }

        public int getBackoffMultiplier() {
            return backoffMultiplier;
        }

        public void setBackoffMultiplier(int backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
        }

        public int getSuccessThreshold() {
            return successThreshold;
        }

        public void setSuccessThreshold(int successThreshold) {
            this.successThreshold = successThreshold;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getLimitForPeriod() {
        return limitForPeriod;
    }

    public void setLimitForPeriod(int limitForPeriod) {
        this.limitForPeriod = limitForPeriod;
    }

    public int getRefillPeriodSeconds() {
        return refillPeriodSeconds;
    }

    public void setRefillPeriodSeconds(int refillPeriodSeconds) {
        this.refillPeriodSeconds = refillPeriodSeconds;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public Anonymous getAnonymous() {
        return anonymous;
    }

    public void setAnonymous(Anonymous anonymous) {
        this.anonymous = anonymous;
    }

    public Redis getRedis() {
        return redis;
    }

    public void setRedis(Redis redis) {
        this.redis = redis;
    }
}
