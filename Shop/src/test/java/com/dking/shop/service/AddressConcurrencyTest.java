package com.dking.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dking.shop.dto.AddressSaveDTO;
import com.dking.shop.entity.UserAddress;
import com.dking.shop.exception.BusinessException;
import com.dking.shop.mapper.UserAddressMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 收货地址并发测试：默认地址唯一、地址数量上限。
 * 并发测试不能用 @Transactional，夹具会留在 doro_mall_test。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class AddressConcurrencyTest {

    private static final int THREADS = 20;
    private static final int MAX_ADDRESS_COUNT = 20;

    @Resource
    private AddressService addressService;

    @Resource
    private UserAddressMapper userAddressMapper;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = IdWorker.getId();
    }

    @Test
    void concurrentAddDefault_shouldKeepOnlyOneDefaultAddress() throws Exception {
        AtomicInteger success = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        runConcurrently(THREADS, index -> {
            try {
                addressService.add(userId, dto(1));
                success.incrementAndGet();
            } catch (Throwable t) {
                unexpected.add(t);
            }
        });

        assertTrue(unexpected.isEmpty(), () -> "并发新增默认地址出现异常: " + unexpected);
        assertEquals(success.get(), countAll(), "成功的请求数应与落库地址数一致");
        assertEquals(1, countDefault(), "同一用户只能有一个默认地址");
    }

    @Test
    void concurrentAddOrdinary_shouldNotExceedMaxCount() throws Exception {
        // 先放一条，避免 count == 0 触发"第一条自动默认"的规则干扰本用例
        addressService.add(userId, dto(1));

        int attempts = MAX_ADDRESS_COUNT + 10;
        AtomicInteger success = new AtomicInteger();
        List<String> businessFailures = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        runConcurrently(attempts, index -> {
            try {
                addressService.add(userId, dto(0));
                success.incrementAndGet();
            } catch (BusinessException e) {
                businessFailures.add(e.getMessage());
            } catch (Throwable t) {
                unexpected.add(t);
            }
        });

        long total = countAll();
        assertTrue(unexpected.isEmpty(), () -> "并发新增地址出现非预期异常: " + unexpected);
        assertTrue(total <= MAX_ADDRESS_COUNT, () -> "地址总数超过上限: " + total);
        assertTrue(businessFailures.stream().allMatch(m -> m != null && m.contains("最多保存")),
                () -> "存在非上限原因的失败: " + businessFailures);
        assertEquals(1, countDefault(), "普通地址不应改变默认地址数量");
    }

    @Test
    void concurrentSetDefault_shouldKeepOnlyOneDefaultAddress() throws Exception {
        for (int i = 0; i < 5; i++) {
            addressService.add(userId, dto(0));
        }
        List<Long> addressIds = userAddressMapper.selectList(
                        new LambdaQueryWrapper<UserAddress>().eq(UserAddress::getUserId, userId))
                .stream()
                .map(UserAddress::getId)
                .toList();

        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        runConcurrently(THREADS, index -> {
            try {
                addressService.update(userId, addressIds.get(index % addressIds.size()), dto(1));
            } catch (Throwable t) {
                unexpected.add(t);
            }
        });

        assertTrue(unexpected.isEmpty(), () -> "并发设置默认地址出现异常（可能是死锁）: " + unexpected);
        assertEquals(1, countDefault(), "并发设置默认后仍然只能有一个默认地址");
    }

    private AddressSaveDTO dto(int isDefault) {
        AddressSaveDTO dto = new AddressSaveDTO();
        dto.setReceiverName("并发测试");
        dto.setReceiverPhone("13800000000");
        dto.setProvince("北京市");
        dto.setCity("北京市");
        dto.setDistrict("海淀区");
        dto.setDetail("测试路 " + IdWorker.getId());
        dto.setIsDefault(isDefault);
        return dto;
    }

    private long countDefault() {
        return userAddressMapper.selectCount(new LambdaQueryWrapper<UserAddress>()
                .eq(UserAddress::getUserId, userId)
                .eq(UserAddress::getIsDefault, 1));
    }

    private long countAll() {
        return userAddressMapper.selectCount(new LambdaQueryWrapper<UserAddress>()
                .eq(UserAddress::getUserId, userId));
    }

    private void runConcurrently(int threads, IndexedTask task) throws Exception {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            int index = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run(index);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    end.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未全部就位");
        start.countDown();
        assertTrue(end.await(60, TimeUnit.SECONDS), "并发任务未在 60s 内结束");
        pool.shutdown();
    }

    @FunctionalInterface
    private interface IndexedTask {
        void run(int index) throws Exception;
    }
}
