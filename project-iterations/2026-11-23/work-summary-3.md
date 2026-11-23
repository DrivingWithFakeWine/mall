# 2026-11-23 第三次改动：营销拆分落地（第 3、4 步）

> 接 `work-summary-2.md`。上一步建好了 `marketing-service` 但没有切流量，
> 这一步完成**双向 MQ 闭环 + 网关切换 + 删除 Shop 里的秒杀实现**。

## 本步目标

| 步 | 内容 | 结果 |
|---|---|---|
| 3 | 交易消费 `seckill.order.create` 建单；取消发 `order.cancelled` 让营销回补 | ✅ |
| 4 | 网关 `/seckill/**` 路由到 marketing（8082） | ✅ |
| 5 | 删除 Shop 里的秒杀实现 | ✅（测试迁移未完，见遗留） |

## 一、交易侧：消费建单消息

`SeckillOrderMessage` 换成新格式，消息里带完整命令：

```java
public record SeckillOrderMessage(String token, SeckillOrderCommand command, long createdAt) {}
```

`SeckillOrderConsumer` 重写，处理三类情况：

| 情况 | 处理 |
|---|---|
| 同 token 已建单（重投） | 直接补写成功结果，不再建单 |
| 永久失败（地址不存在、商品下架、重复下单） | 写失败结果 + 发 `seckill.order.failed` 事件 |
| 暂时失败（DB 抖动） | 直接抛出，交给 MQ 重试，预扣先留着 |

幂等靠 `orders.order_token`：先按 token 查一次，撞唯一键时也按「已建单」处理。

## 二、交易 → 营销的反馈事件

新增两个事件，走独立交换机 `doroshop.seckill.feedback`：

| 事件 | 触发 | 营销的处理 |
|---|---|---|
| `SeckillOrderFailedEvent` | 建单永久失败 | 回补桶 + 释放 Redis 预扣（允许重试） |
| `OrderCancelledEvent` | 秒杀订单取消/超时取消 | 回补桶 + 回补 Redis 活动库存 |

生产者 `SeckillFeedbackPublisher` 发布失败只记日志——取消操作本身已经成功，
不能因为「通知没发出去」把用户的取消判成失败；漏掉的那次回补由对账兜底。

消费者 `SeckillFeedbackListener` 用两个独立队列，重复消费靠桶的
`available + n <= total` 约束做幂等。

**至此 `OrderServiceImpl` 不再依赖任何营销服务**：删掉了 `SeckillBucketStockService` /
`SeckillStockService` 两个字段，取消路径只发事件。

## 三、删除 Shop 的秒杀实现

删掉 20 个类：

```
controller/SeckillController
service/SeckillService + impl/SeckillServiceImpl
service/SeckillStockService + impl/SeckillStockServiceImpl      （Redis 闸门）
service/SeckillBucketStockService + impl/SeckillBucketStockServiceImpl （库存桶）
service/SeckillOrderPublisher + impl/RabbitSeckillOrderPublisher
service/support/SeckillActivityCache、SeckillActivitySnapshot
entity/SeckillActivity、SeckillStockBucket
mapper/SeckillActivityMapper、SeckillStockBucketMapper
dto/SeckillOrderDTO
vo/SeckillActivityDetailVO、SeckillActivityListItemVO
task/SeckillStockTask
exception/SeckillSoldOutException
```

Shop 只保留三样：`SeckillOrderConsumer`（消费建单）、`SeckillOrderResultStore`（写结果）、
`SeckillOrderCommand`（契约）。

## 四、网关切换

```yaml
- id: shop-marketing
  uri: ${MARKETING_SERVICE_URI:http://localhost:8082}
  predicates:
    - Path=/seckill/**
```

## 五、测试

删除 4 个秒杀测试类（26 个用例），它们依赖已经搬走的类：

```
SeckillOrderTest(14) SeckillStockServiceTest(7)
SeckillActivityDetailCacheTest(4) SeckillRedisUnavailableTest(1)
```

**这是在还迁移的债**：这些用例要重写成营销服务的测试，见「遗留」。

新增 `Shop/src/test/resources/application-test.yaml`：

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        auto-startup: false   # 测试不依赖 broker
```

否则每个 `@SpringBootTest` 都要先起 RabbitMQ 才能加载上下文。
`ShopApplicationTests` 补上 `@ActiveProfiles({"local","test"})`，和其它集成测试保持一致。

## 验证

```
marketing-service  mvn package → 0
Shop               mvn package → 0
gateway            mvn package → 0

Shop 测试：15 个类 / 63 个用例 / 0 失败（原 89，去掉 26 个秒杀用例）
```

**端到端（三个进程都起）**：

| 请求 | 结果 |
|---|---|
| `GET localhost:9000/seckill/list` | `200 {"records":[],"total":0}` —— 由营销服务返回 |
| `GET localhost:9000/order/list`（无 token） | `401` —— 网关鉴权 |

## 补充：测试补齐（含两个消息丢失场景）

### 营销侧（marketing-service，15 个用例）

| 测试类 | 用例 | 覆盖 |
|---|---|---|
| `SeckillStockServiceTest` | 7 | Redis 闸门：预扣、去重、售罄、时间窗、释放、回补、幂等初始化 |
| `SeckillSubmitTest` | 5 | 下单流程：正常扣减 + 发消息、重复提交、商品下架回滚、入队失败回滚、**消息没人消费** |
| `SeckillFeedbackListenerTest` | 3 | 收到反馈事件后回补桶 + Redis；重复投递不超总量 |

### 交易侧（Shop，6 个用例）

| 测试类 | 用例 | 覆盖 |
|---|---|---|
| `OrderCancelFeedbackTest` | 2 | 秒杀订单取消发事件；普通订单不发 |
| `SeckillFeedbackPublisherTest` | 4 | 事件发布与路由；MQ 挂掉时**不向上抛异常** |

### 测试基础设施

- `LocalProductClient`（`@Profile("test")`）：内存注册 SKU 快照，营销测试不连商品服务
- `RecordingSeckillOrderPublisher`（`@Profile("test")`）：截获建单消息，可切换为「发布失败」
- `RabbitSeckillOrderPublisher` 加 `@Profile("!test")`；`SeckillOrderConsumer` 加 `@Profile("!test")`
  （`spring.rabbitmq.listener.simple.auto-startup=false` 在这个版本没生效，改用 profile 隔离更确定）
- 营销 `ProductClient` 裁剪成只读两个方法（批量快照 + 关键字搜索），删掉 3 个库存写 DTO

### 场景 A：MQ 发出去了，订单服务没收到

`SeckillSubmitTest.messagePublishedButNeverConsumed_shouldKeepStockDeductedUntilReconcile`

```java
SeckillOrderSubmitVO vo = seckillService.submitOrder(USER_ID, activityId, dto());
// 消息在队列里，但没有任何消费者
assertEquals(1, publisher.published().size());
// 库存被占住：既不能卖给别人，也还没变成订单
assertEquals(STOCK - 1, seckillStockService.currentStock(activityId).intValue());
assertEquals(STOCK - 1, bucketStockService.totalAvailable(activityId));
assertEquals(SeckillOrderSubmitVO.PENDING, orderResultStore.get(vo.getToken()).status());
```

**这条测试固定的是「丢失模式的代价」**：Redis 和数据库两个源各扣了 1，用户一直看到 PENDING，
营销没有任何手段自愈——只能靠对账把「超时未建单」的预占还回来。

### 场景 B：订单收到了，但反馈 MQ 丢了

这一半拆成两段验证：

1. **交易侧只负责发**（`OrderCancelFeedbackTest`）：取消后订单变已取消，事件带上了
   `orderNo / activityId / stockBucket / quantity`；普通订单不发这个事件。
2. **发布失败不能影响业务**（`SeckillFeedbackPublisherTest`）：broker 抛异常时发布器吞掉，
   取消仍然成功——否则会出现「提示取消失败、订单其实已取消」。
3. **营销收到就回补**（`SeckillFeedbackListenerTest`）：喂事件进监听器，桶和 Redis 都回满。

中间「发了但没收到」的那一段没有代码兜底，和场景 A 一样只能靠对账——这是设计上主动接受的
「宁可少卖，不可卖多」。

### 验证结果

```
marketing-service  15 个用例全绿（7 + 5 + 3）
Shop               17 个类 / 69 个用例全绿
```

## 遗留

- **订单取消链路未端到端验证**：需要造一条秒杀订单再取消，观察桶和 Redis 是否回补
  （单独的回补逻辑已被 `SeckillFeedbackListenerTest` 覆盖，缺的是跨进程串联）
- **`SeckillOrderTest` 没有直接迁移**：原用例断言「提交后订单落库」，跨服务后这个断言属于交易侧，
  已由 `OrderCancelFeedbackTest` + 营销的提交测试分别覆盖，但原用例里的并发抢购场景没有等价替代
- **RabbitMQ 账号**：本地容器用的是 `doroshop` 账号，跑服务要带
  `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` 环境变量，默认的 guest/guest 连不上
- **营销还没限流**：`/seckill/{id}/order` 的高频请求目前只有 Redis 闸门挡，
  网关限流规则还没按路径细化
- **未提交**：本轮改动尚未 commit / push
