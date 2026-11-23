package com.dking.marketing.service.impl;

import com.dking.marketing.dto.SeckillOrderMessage;
import com.dking.marketing.service.SeckillOrderPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 记录型发布器：测试里不发 MQ，只把消息存下来供断言。
 *
 * <p>用它才能区分「消息发出去了但没人消费」（丢消息场景）和「根本没发出去」——
 * 前者是运维/对账要兜的，后者是代码 bug。</p>
 */
@Component
@Profile("test")
public class RecordingSeckillOrderPublisher implements SeckillOrderPublisher {

    private final List<SeckillOrderMessage> published = new CopyOnWriteArrayList<>();

    /** 打开后 publish 直接抛异常，模拟 broker 不可用 */
    private volatile boolean failing;

    @Override
    public void publish(SeckillOrderMessage message) {
        if (failing) {
            throw new IllegalStateException("模拟 MQ 不可用");
        }
        published.add(message);
    }

    public List<SeckillOrderMessage> published() {
        return published;
    }

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public void clear() {
        published.clear();
        failing = false;
    }
}
