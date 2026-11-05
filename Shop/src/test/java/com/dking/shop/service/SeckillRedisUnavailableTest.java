package com.dking.shop.service;

import com.dking.shop.entity.SeckillActivity;
import com.dking.shop.exception.ServiceUnavailableException;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** Redis 不可用时必须快速失败，不能降级到 DB。 */
@SpringBootTest(properties = {
        "spring.data.redis.port=6399",
        "spring.data.redis.connect-timeout=200ms",
        "spring.data.redis.timeout=200ms"
})
@ActiveProfiles({"local", "test"})
class SeckillRedisUnavailableTest {

    @Resource
    private SeckillStockService seckillStockService;

    @Test
    void reserve_shouldFailFastWhenRedisUnavailable() {
        SeckillActivity activity = new SeckillActivity();
        activity.setId(1L);
        activity.setSeckillPrice(new BigDecimal("9.90"));
        activity.setTotalStock(10);
        activity.setAvailableStock(10);
        activity.setStartTime(LocalDateTime.now().minusMinutes(1));
        activity.setEndTime(LocalDateTime.now().plusHours(1));
        activity.setStatus(1);

        assertThrows(ServiceUnavailableException.class,
                () -> seckillStockService.reserve(activity, 1L));
    }
}
