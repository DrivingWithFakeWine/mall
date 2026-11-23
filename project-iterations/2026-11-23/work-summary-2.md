# 2026-11-23 第二次改动：营销拆分启动（订单 ↔ 营销 双向 MQ）

> 本篇接 `work-summary.md`（第一次改动：购物车/秒杀改读商品服务 + 商品代码清理）。
> 本篇记录营销域拆分的**决策与第一步落地**。

## 决策：订单与营销之间双向都走 MQ

| 方向 | 事件 | 生产者 | 消费者 | 幂等依据 |
|---|---|---|---|---|
| 营销 → 订单 | `seckill.order.create` | marketing | trade | `orders.order_token` 唯一索引 |
| 订单 → 营销 | `order.cancelled` | trade | marketing | 桶回补按订单记录的 `stock_bucket` 定点回补，重复回补会被 `available + n <= total` 挡住 |

**为什么不用同步 RPC 做反向回补**（这是我原来的推荐，被否掉了，理由更充分）：

- 同步调用会形成「marketing → trade 建单、trade → marketing 回补」的**双向依赖**，任一方故障都放大成双边故障
- 回补是**可延迟**的动作：晚几百毫秒只是少卖一会儿，不会卖错
- 取消流程本来就是「状态先落库、后做补偿」的模型，MQ 天然贴合；同步 RPC 反而要把「补偿失败怎么办」再写一遍

代价：两个域都要有消费者、要处理重投。但两边都有天然的幂等键（token、桶号），成本可控。

## 本次代码改动

**1. 新增建单契约 `SeckillOrderCommand`**

`com.dking.shop.dto.SeckillOrderCommand`，12 个扁平字段，不引用任何营销域实体：

```
userId, activityId, stockBucket, orderToken, addressId,
spuId, skuId, productName, specDesc, image, price, remark
```

商品名称/规格/图片是**快照**，随命令一起传——建单方回查商品服务可能拿到改价后的新值，快照就失真了。
这个 record 就是将来 `seckill.order.create` 消息的 payload，拆成两个服务时不用改字段。

**2. 建单搬进订单域**

`OrderService.createSeckillOrder(SeckillOrderCommand)`，实现里只碰订单域的三张表：

```
orders → order_item → order_address（快照）
```

和普通下单共用 `loadAddress` / `insertAddressSnapshot`，行为保持一致。

**3. 营销域不再写订单表**

`SeckillServiceImpl.doSubmit` 现在只做三件事：

```
校验 SKU（走 ProductClient）→ 扣活动桶 → 组装 SeckillOrderCommand → 调 OrderService
```

删掉了 `OrderItemMapper` / `OrderAddressMapper` 两个依赖，以及 `ORDER_STATUS_WAIT_PAY` /
`SINGLE_MERCHANT_ID` / `PAY_TIMEOUT_MINUTES` 三个只为建单存在的常量。

## 验证

```
SeckillOrderTest            14 ✅
SeckillActivityDetailCacheTest  4 ✅
SeckillStockServiceTest      7 ✅
SeckillRedisUnavailableTest  1 ✅
OrderControllerIntegrationTest  7 ✅
```

秒杀链路行为不变：Redis 闸门、桶扣减、异步消费、结果轮询都没动，只是最后一步从「自己 insert」变成「调服务」。

## 剩余步骤

| 步 | 内容 | 状态 |
|---|---|---|
| 1 | 建单从营销抽到订单域 | ✅ 完成 |
| 2 | 新建 `marketing-service`：活动、闸门、桶、缓存、结果轮询、发布消息 | 🟡 模块已落地并可启动，未切流量 |
| 3 | 订单侧发 `order.cancelled`（取消 + 超时取消两条路径） | ⬜ |
| 4 | 网关 `/seckill/**` 路由到 marketing | ⬜ |
| 5 | 删 Shop 里的秒杀代码，测试迁移过去 | ⬜ |

### 第 2 步落地情况（2026-11-23 晚）

新建模块 `marketing-service`（端口 8082，包 `com.dking.marketing`），已迁入：

| 分类 | 内容 |
|---|---|
| 活动 | `SeckillActivity` / `SeckillStockBucket` 实体与 Mapper、`SeckillActivityCache`、`SeckillStockTask` |
| 闸门 | `SeckillStockService`（Lua 预扣 + 用户占位）、`SeckillActivitySnapshot` |
| 桶 | `SeckillBucketStockService`（8 桶、随机起始探测） |
| 结果 | `SeckillOrderResultStore`（Redis 结果缓存 + 轮询） |
| 消息 | `SeckillOrderPublisher` / `RabbitSeckillOrderPublisher`、`SeckillAsyncConfig`（交换机/队列/DLQ） |
| 接口 | `SeckillController`：列表、详情、下单、结果轮询 |

**鉴权方式改了**：营销自己不再验 JWT，信任网关注入的 `X-User-Id`。
理由：JWT 由网关验一次就够，下游重复验签只是多一次解析；服务不对外暴露，直连也拿不到有效头。

**下单流程改成 MQ 单向**：

```
Redis 闸门预扣 → 校验商品（ProductClient）→ 扣活动桶 → 发布 SeckillOrderMessage → 返回 PENDING + token
```

任一步失败（商品下架、桶扣空、入队异常）都会把**桶和 Redis 预扣一起回滚**，保证「有货才有单」。
消息体是 `{token, SeckillOrderCommand, createdAt}`，建单所需的价格和商品快照全部随消息走，
消费方不需要回查活动或商品。

**已验证**：`mvn -DskipTests package` 通过；启动后

```
GET /seckill/list?page=1&size=2  → 200 {"records":[],"total":0}
GET /seckill/1                   → 404 "秒杀活动不存在"
```

**未做**：网关还没把 `/seckill/**` 切到 8082，Shop 里的秒杀代码也还在。
当前两个服务各有一份闸门代码，**同时接流量会写同一批 Redis key**，所以切换前不能让营销对外提供服务。

## 事件载荷（草案）

**`seckill.order.create`**（沿用现有 `SeckillOrderMessage` 的字段）

```
token, userId, activityId, addressId, remark
```

消费方（trade）收到后：查活动价格和商品快照 → 建单 → 结果写回 Redis（`SeckillOrderResultStore`）。
注意：活动价格和商品快照现在由营销组装进 `SeckillOrderCommand`；拆成两个服务后，
要么消息里带上这些字段（推荐，消费方不再回查），要么消费方自己查——前者更稳。

**`order.cancelled`**

```
orderNo, activityId, stockBucket, quantity, cancelledAt
```

消费方（marketing）收到后：`bucketStockService.restore(activityId, bucket, quantity)` +
`seckillStockService.restoreStock(activityId, quantity)`。非秒杀订单不发这条消息。

## 已知问题

- **事件还没真正落地**：这次只是把代码结构改成了「可消费」的形态，MQ 生产/消费在步骤 3 才接上
- **建单仍在一个本地事务里**：变成 MQ 消费后，要确保 `order_token` 唯一索引挡住重投，
  并且失败重试时不会重复扣活动库存（扣减在营销侧，早于消息投递）
- **测试仍依赖同进程**：步骤 5 迁移时，marketing-service 的测试需要一个「建单」替身，
  类似 `LocalProductClient` 的做法
- **未提交**：本轮改动尚未 commit / push

## 补充：两个并发 / 错误处理问题（代码评审发现）

### 1. 下单结果写入的 write-after-read

原实现（`submitOrder` 末尾）：

```java
publisher.publish(message);        // ① 发消息
orderResultStore.markPending(token, userId);   // ② 写 PENDING
```

消费者可能在 ① 返回之前就消费了消息、把 **SUCCESS** 写进 Redis；随后 ② 用 PENDING 覆盖回去。
结果是订单已经建好了，前端却一直轮询到超时，用户以为没下单成功——典型的 write-after-read 丢更新。

**修复**：把 `markPending` 提到 `publish` 之前先占位，并在入队失败的分支里
`orderResultStore.remove(token)` 清掉占位，避免留下一个永远 PENDING 的 key。

```java
orderResultStore.markPending(token, userId);   // ① 先占位
try {
    publisher.publish(message);                // ② 再发消息
} catch (RuntimeException e) {
    orderResultStore.remove(token);            // ③ 失败清理
    restoreQuietly(activity, bucketNo);
    releaseQuietly(activityId, userId, false);
    throw new ServiceUnavailableException("秒杀服务暂不可用，请稍后重试");
}
```

同一个问题在 Shop 的 `SeckillServiceImpl.submitOrder` 里也存在（它还在接流量），一并修了。

### 2. 读结果时把「基础设施故障」当成「订单不存在」

原实现：

```java
} catch (RuntimeException e) {
    log.warn("读取秒杀下单结果失败, token={}", token, e);
    return null;          // ← Redis 故障也返回 null
}
```

调用方拿到 null 就抛 `NotFoundException` → 前端收到 404「订单不存在或结果已过期」，
于是**停止轮询**。但实际上订单可能已经建好了，只是这一次 Redis 读失败了。

**修复**：`get()` 只在 key 不存在时返回 null；Redis 或反序列化异常改为抛
`ServiceUnavailableException`（503），前端据此提示「重试」而不是「订单不存在」。

```java
public StoredResult get(String token) {
    try {
        String value = redis.opsForValue().get(key(token));
        return value == null ? null : jsonMapper.readValue(value, StoredResult.class);
    } catch (RuntimeException e) {
        log.error("读取秒杀下单结果失败, token={}", token, e);
        throw new ServiceUnavailableException("查询下单结果失败，请稍后重试");
    }
}
```

`null` 的语义收窄成唯一一种：**结果不存在或已过期**，接口返回 404。

### 验证

```
marketing-service  mvn compile → 0
Shop               mvn compile → 0
Shop 秒杀相关测试   26 个用例全绿（14 + 4 + 7 + 1）
```
