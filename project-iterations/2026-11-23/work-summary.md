# 2026-11-23 迭代工作整理

## 本迭代目标

2026-11-22 把订单链路改成了调商品服务，但**购物车和秒杀还在直读 `product_*` 表**——Shop 进程里仍留着两份商品数据访问路径。

本轮把这两处也切到商品服务的内部接口，让 Shop 主代码不再依赖商品 Mapper。

## 已完成事项

| 事项 | 结果 |
|---|---|
| 购物车改造 | `add` / `list` / `updateQuantity` 三个入口全部改用 `ProductClient.batchSkus`，删除对商品 Mapper 的依赖 |
| 秒杀改造 | 列表、详情、下单校验改用 `batchSkus`；关键字搜索改用 `searchSpuIds` |
| 客户端补接口 | `ProductClient` 新增 `GET /internal/product/search/spu-ids`，测试替身同步实现 |
| 测试修复 | 3 类共 4 个用例因接口变化失效，见下一节 |
| Mapper 补充 | `SeckillStockBucketMapper.deleteByActivity`（测试清理用，自定义 Mapper 没有 `BaseMapper` 的 `delete(Wrapper)`） |
| 测试库清理 | 删除 78 个残留在线活动 |
| Shop 商品代码清理 | 主代码删除 8 个商品类 + 7 个商品 VO；仍被测试当夹具的 4 实体 / 4 Mapper / `ProductSearchSupport` 移到 `src/test` |

## 关键决策

**1. 购物车和秒杀的库存校验，从「直读」变成「RPC 读」**

代价是每次加购/列表多一次内部调用。为什么接受：

- 这些接口本来就是读操作，调用量远低于商品详情页
- `batchSkus` 一次拿回「SKU + 库存 + SPU 摘要」，避免调用方连查三次
- 拆分的意义就是让商品数据只有一个出口；留一条直读路径，schema 一改就要改两处

**2. 快照接口把 SPU 字段并进来**

`SkuSnapshot` 里带 `spuName` / `spuMainImage` / `spuStatus`，购物车和秒杀都不需要再单独查 SPU。
代价是商品服务的接口要 JOIN 一次，但这个成本落在服务端，比每个调用方各查一次划算。

**3. 契约测试改成断言 404，而不是删掉用例**

`/product/list`、`/category/tree` 已经迁到商品服务，Shop 不再提供。保留断言 404 是为了**把「边界已经挪走」这件事固定在测试里**——以后谁把商品 Controller 加回来，测试会立刻报错。

**4. 秒杀关键字搜索走 RPC，测试替身仍用本地实现**

生产路径是 `productClient.searchSpuIds`；测试替身 `LocalProductClient` 用 `ProductSearchSupport` 实现。
本轮收尾时把 `ProductSearchSupport`、4 个商品实体、4 个商品 Mapper **整体移到了 `src/test`**：
它们是测试夹具，主代码里已经零商品依赖。其余 8 个没人用的商品类和 7 个商品 VO 直接删除。

**5. 购物车和秒杀是 product 的「纯读者」**

把调用方按读/写分开看，边界很清楚：

| 调用方 | 读（batchSkus / searchSpuIds） | 写（reserve / release / confirm / sales） |
|---|---|---|
| 购物车 | ✅ 加购、列表、改数量 | ❌ 完全不涉及 |
| 秒杀（营销域） | ✅ 列表、详情、下单校验 | ❌ 库存走 `seckill_stock_bucket`，不碰 `product_stock` |
| 订单 | ✅ 下单前查 SKU | ✅ 四个写接口只有它在用 |
| 秒杀订单取消 | ❌ | ❌ 回补活动桶 + Redis，不碰商品服务 |

唯一的交叉：秒杀订单**支付**时，订单模块会调 `sales` 累加商品销量（`activityId != null` 时跳过 `confirm`，但 `sales` 照调）。

这条划分说明 product 其实承载了两个关注点：

- **目录**（`product_spu / product_sku`）：读多写少，可缓存、可降级，消费者有 3 个
- **库存**（`product_stock`）：写热点，要幂等 + 对账，消费者只有订单 1 个

两者的扩容画像和一致性要求完全不同。现在合在一个进程里没问题，但这条线意味着将来边界再挪，很可能是 **catalog 和 inventory 拆开**，库存只对交易域开放（`DECISIONS` 第 12 节「触发重审条件」里已经埋了这条）。

## 验证内容

**全量测试**

```
测试类: 19   用例: 89   失败+错误: 0
```

用例数从 95 降到 89，原因是：
- `ProductSearchTest`（10 个）已随领域迁到 product-service
- `HttpContractIntegrationTest` 的两个商品接口用例合并成一个

**过程中发现并修掉的 3 个测试问题**

| 现象 | 根因 | 处理 |
|---|---|---|
| `CartUpdateQuantityTest` 2 个用例报「库存不足」 | 夹具只插了 `product_stock`，没有 SPU/SKU 行，新的快照接口查不到 | 夹具补插 SPU + SKU |
| `HttpContractIntegrationTest` 2 个用例报 404 | 商品接口 11-18 已迁走，用例还在断言 200 | 合并成一个用例，断言 404 并注明原因 |
| `SeckillOrderTest` 列表用例偶发失败 | `@AfterEach` 只清 Redis 不清库表，测试库堆了 **78 个在线活动**；列表按 `start_time` 升序分页，超过 50 条后新活动被挤出第一页 | 两个秒杀测试类补上库表清理；清空测试库残留活动 |

第三个是**测试数据卫生问题**，不是本轮改出来的，但被本轮的全量回归暴露了。

## 产物

| 文件 | 类型 | 说明 |
|---|---|---|
| `Shop/.../service/impl/CartServiceImpl.java` | 改写 | 三个入口改调 `batchSkus` |
| `Shop/.../service/impl/SeckillServiceImpl.java` | 改写 | 列表/详情/下单校验/关键字搜索改调 RPC |
| `Shop/.../client/ProductClient.java` | 修改 | 新增 `searchSpuIds` |
| `Shop/.../mapper/SeckillStockBucketMapper.java` | 修改 | 新增 `deleteByActivity` |
| `Shop/src/test/.../client/LocalProductClient.java` | 修改 | 实现 `searchSpuIds` |
| `Shop/src/test/.../controller/HttpContractIntegrationTest.java` | 修改 | 商品接口改用例为 404 断言 |
| `Shop/src/test/.../service/CartUpdateQuantityTest.java` | 修改 | 夹具补 SPU/SKU |
| `Shop/src/test/.../service/SeckillOrderTest.java` | 修改 | `@AfterEach` 补库表清理 |
| `Shop/src/test/.../service/SeckillStockServiceTest.java` | 修改 | 同上 |
| `Shop/src/main/.../entity/`、`mapper/`、`vo/` | 删除 | 8 个商品实体 + 4 个商品 Mapper + 7 个商品 VO（主代码不再引用） |
| `Shop/src/main/.../entity, mapper, service/support` → `src/test` | 移动 | ProductSku/Spu/Stock/Category + 对应 Mapper + ProductSearchSupport（测试夹具） |

## 已知问题 / 下一步

- **读商品现在依赖商品服务可用性**：商品服务挂掉时，加购和秒杀列表会直接失败（没有降级缓存）。
  订单链路有「等待对账」兜底，读路径没有，这是后续可以考虑补的。
- **商品代码只剩下测试夹具**：`src/test` 里保留 4 个实体 + 4 个 Mapper + `ProductSearchSupport`，
  只为给用例造数据；哪天下决心把测试也改成 JdbcTemplate，这几个类可以彻底删掉。
- **文档过期**：根 `README.md` 和 `project-iterations/README.md` 里的「95 个测试」已不准确（现 89）；
  迭代时间线也还没加 11-23 这一行。
- **未提交**：本轮改动尚未 commit / push。
