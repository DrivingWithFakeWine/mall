package com.dking.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dking.shop.dto.RegisterDTO;
import com.dking.shop.entity.DoroUser;
import com.dking.shop.exception.BusinessException;
import com.dking.shop.mapper.DoroUserMapper;
import jakarta.annotation.Resource;
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
 * 并发注册：唯一索引保证不会重复注册，但冲突请求应返回业务异常而不是系统异常。
 * 并发测试不能用 @Transactional，注册数据会留在 doro_mall_test。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class UserRegisterConcurrencyTest {

    private static final int THREADS = 20;

    @Resource
    private DoroUserService userService;

    @Resource
    private DoroUserMapper doroUserMapper;

    @Test
    void concurrentRegisterSameUsername_shouldSucceedOnceAndReturnBusinessError() throws Exception {
        String username = uniqueUsername();
        String telephone = uniquePhone();

        AtomicInteger success = new AtomicInteger();
        List<String> businessFailures = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        runConcurrently(THREADS, index -> {
            try {
                userService.register(dto(username, telephone));
                success.incrementAndGet();
            } catch (BusinessException e) {
                businessFailures.add(e.getMessage());
            } catch (Throwable t) {
                unexpected.add(t);
            }
        });

        assertEquals(1, success.get(), "同名并发注册只能成功一次");
        assertTrue(unexpected.isEmpty(), () -> "出现非业务异常（唯一键冲突未转换）: " + unexpected);
        assertEquals(THREADS - 1, businessFailures.size(), "其余请求应返回业务异常");
        assertEquals(1, countByUsername(username).intValue(), "数据库只能有一个同名用户");
    }

    @Test
    void concurrentRegisterSameTelephone_shouldSucceedOnceAndReturnBusinessError() throws Exception {
        String telephone = uniquePhone();
        String[] usernames = new String[THREADS];
        for (int i = 0; i < THREADS; i++) {
            usernames[i] = uniqueUsername();
        }

        AtomicInteger success = new AtomicInteger();
        List<String> businessFailures = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        runConcurrently(THREADS, index -> {
            try {
                userService.register(dto(usernames[index], telephone));
                success.incrementAndGet();
            } catch (BusinessException e) {
                businessFailures.add(e.getMessage());
            } catch (Throwable t) {
                unexpected.add(t);
            }
        });

        assertEquals(1, success.get(), "同手机号并发注册只能成功一次");
        assertTrue(unexpected.isEmpty(), () -> "出现非业务异常（唯一键冲突未转换）: " + unexpected);
        assertEquals(THREADS - 1, businessFailures.size(), "其余请求应返回业务异常");
        assertEquals(1, countByTelephone(telephone).intValue(), "数据库只能有一个该手机号的用户");
    }

    private RegisterDTO dto(String username, String telephone) {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername(username);
        dto.setNickname("并发用户");
        dto.setPassword("123456");
        dto.setTelephone(telephone);
        return dto;
    }

    private String uniqueUsername() {
        // 用户名校验规则：字母开头，4-16 位字母/数字/下划线
        return "u" + Long.toString(IdWorker.getId(), 36);
    }

    private String uniquePhone() {
        return "13" + String.format("%09d", Math.floorMod(IdWorker.getId(), 1_000_000_000L));
    }

    private Long countByUsername(String username) {
        return doroUserMapper.selectCount(new LambdaQueryWrapper<DoroUser>()
                .eq(DoroUser::getUserName, username));
    }

    private Long countByTelephone(String telephone) {
        return doroUserMapper.selectCount(new LambdaQueryWrapper<DoroUser>()
                .eq(DoroUser::getTelephone, telephone));
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
