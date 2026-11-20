# product-service 阅读指南

> 目的：通读前先知道「哪些是搬的、哪些是新写的」，避免把搬过来的代码当成新设计去琢磨。
> 结论先说：**38 个原样搬移、3 个改过、14 个新写。** 真正的设计增量只在最后 14 个里。

## 一、盘点

| 类别 | 数量 | 说明 |
|---|---|---|
| 原样搬移 | 38 | 只改了包名 `com.dking.shop` → `com.dking.product`，逻辑一行没动 |
| 改过 | 3 | `ProductStockMapper`、`ProductSkuMapper`、`ProductSpuMapper`，各加了几个 `@Update` 方法 |
| 新写 | 14 | 见下 |

新写的 14 个：

```
ProductApplication.java              启动类（@MapperScan）
controller/InternalProductController 内部查询接口
controller/InternalStockController   内部写接口
service/SkuQueryService              批量查 SKU 快照
service/StockCommandService          ★ 库存写：幂等 + 状态机 + 事务
dto/SkuSnapshot                      跨服务读模型
dto/StockItem / StockOpRequest       写请求
dto/StockOpResult / StockErrorResponse 写结果 / 错误体
entity/StockOpLog                    幂等台账实体
mapper/StockOpLogMapper              台账 upsert
exception/StockErrorCode             机器可读错误码
exception/StockOpException           带错误码的异常
```

## 二、为什么大部分是原样搬的

这是拆分项目的正常节奏，不是偷懒：

1. **先搬再改**。搬迁和重构混在一起，出了 bug 分不清是搬错了还是改错了。
2. **搬迁必须零逻辑变更**，否则无法用「新旧接口返回一致」来验证拆对了。
3. 真正需要改的是**跨进程带来的问题**（网络、重试、事务边界），这些问题只在「调用方」和「写入方」出现，查询代码原样搬过去就能跑。

所以看代码时的正确姿势：**38 个搬移文件扫一眼就行，精力放在 14 个新文件上。**

## 三、三层差异

### 第 1 层：结构（一眼可见，但价值最低）

| 单体 | product-service |
|---|---|
| 和订单/用户/秒杀同一个进程 | 独立进程，8081 |
| 端口 8080 | 8081 |
| `Shop/src/main/resources/application.yaml` | 自己的 `application.yaml` |
| 包名 `com.dking.shop.*` | `com.dking.product.*` |
| 被网关和单体共用 | 只被网关（和未来的交易服务）调用 |

这一层是「拆开了」，但没有「改好了」。

### 第 2 层：契约（两套接口并存）

这是最容易看懂、也最值得学的一点：**同一个域，对浏览器和对其他服务，接口形状应该不一样。**

| | 对外 `ProductController` | 内部 `InternalProductController` |
|---|---|---|
| 消费者 | 浏览器 | 其他服务 |
| 分页 | 有 | 无（按 id 批量） |
| 返回 | `PageVO` 包一层 | 扁平 `List<SkuSnapshot>` |
| 数据缺失 | 抛 `NotFoundException` | **静默跳过**，让调用方自己判断 |
| 错误语义 | HTTP 200 + `Result.code`（面向用户） | HTTP 409 + 字符串错误码（面向程序） |
| 路径 | `/product/**` | `/internal/product/**`（网关不转发） |

**关键判断**：购物车里混着下架商品是常态，所以内部批量查询不能因为「有一个 id 查不到」就抛异常——那会让调用方每次都要 try-catch 处理正常情况。

### 第 3 层：写入（唯一有真正新设计的地方）

这是整个拆分里唯一「不能靠搬家解决」的部分。

**单体里，扣库存是一行 SQL：**

```java
int rows = productStockMapper.update(null,
        new LambdaUpdateWrapper<ProductStock>()
                .eq(ProductStock::getSkuId, cart.getSkuId())
                .ge(ProductStock::getAvailableStock, cart.getQuantity())
                .setSql("available_stock = available_stock - " + cart.getQuantity())
                .setSql("locked_stock = locked_stock + " + cart.getQuantity()));
if (rows == 0) throw new BusinessException("库存不足：" + spu.getName());
```

**拆出去之后，扣库存变成一个协议：**

```
1. 校验参数
2. 批量读台账 → 这个订单这个 SKU 之前执行过什么
3. 状态机判断：该执行、该跳过、还是该拒绝
4. 写台账（唯一键幂等）
5. 条件更新库存（available >= n）
6. 失败 → 整个事务回滚（含台账）
```

多出来的 5 步，每一步都对应一个只有跨进程才会出现的问题：

| 新增的步骤 | 解决什么问题 |
|---|---|
| 台账 | 网络超时 → 调用方重试 → 不能重复扣 |
| 状态机 | 没预占就释放 → 会偷走同 SKU 其他订单的锁 |
| 事务边界 | 多明细部分失败 → 不能留下"扣了一半" |
| 错误码 | 调用方要知道「该重试还是该报错」 |
| before/after 快照 | 出问题时能查「那一刻库存是多少」 |

**这就是「单体拆微服务」真正的工作量所在**：不是把类挪个地方，是把「方法调用」变成「有幂等、有状态、有错误语义的协议」。

## 四、建议阅读顺序

按这个顺序读，从易到难，每一步都有明确目的：

| 顺序 | 文件 | 看什么 |
|---|---|---|
| 1 | `pom.xml` + `application.yaml` | 独立服务的依赖和配置，比单体少了哪些（没有 security、没有 redis） |
| 2 | `ProductApplication.java` | `@MapperScan` 指向自己的包 |
| 3 | `ProductController` + `CategoryController` | 和单体一模一样，确认「搬迁零改动」 |
| 4 | `InternalProductController` + `SkuQueryService` | **对比点**：面向服务的接口为什么字段更扁、缺数据为什么不报错 |
| 5 | `dto/SkuSnapshot` | 跨服务读模型的字段取舍（为什么要带 spuName/spuMainImage） |
| 6 | `ProductStockMapper` | 三个条件更新 SQL：`available >= n`、`locked >= n` 的方向 |
| 7 | `StockOpLog` + `StockOpLogMapper` | 幂等键 + `ON DUPLICATE KEY UPDATE id=id` 的写法 |
| 8 | **`StockCommandService`** | **核心**：`validateAndNormalize` → `loadLedger` → `decide` → `mark` → `updateStock` |
| 9 | `InternalStockController` | 200/409/5xx 的分工，以及为什么错误体不是 `Result` |
| 10 | `StockErrorCode` | 错误码怎么划（可重试 vs 不可重试） |
| 11 | `sql/migration_*.sql` | 表设计：ascii 字符集、CHECK 约束、DATETIME(3)、before/after 列 |

第 8 步是重点，建议对照着 `decide()` 的 switch 看状态机：

```
(无记录) --RESERVE--> RESERVED --RELEASE--> RELEASED
                         └------CONFIRM----> CONFIRMED
```

## 五、三个可以直接对比的点

### 对比 1：库存不足怎么报

| 单体 | 新服务 |
|---|---|
| `throw new BusinessException("库存不足：" + spu.getName())` | `throw new StockOpException(INSUFFICIENT_STOCK, "库存不足: skuId=...")` |
| 前端拿 `Result.code=400` | 调用方拿 HTTP 409 + `code=INSUFFICIENT_STOCK` |

单体里调用方是「自己」，知道异常类型；拆开后调用方是另一个进程，只能靠状态码和字符串码判断。

### 对比 2：同一个 SKU 出现两次

单体：购物车表有 `uk_user_sku`，天然不会重复。

新服务：调用方可能传 `[{sku:1,qty:1},{sku:1,qty:2}]`。如果不合并，第二条会命中台账唯一键被当成"重试"跳过，**数量静默丢失**。所以 `validateAndNormalize` 先合并成 `{sku:1,qty:3}`。

### 对比 3：事务边界扩大了

单体：`submit()` 一个事务覆盖 6 张表。

新服务：`reserve()` 一个事务只覆盖「台账 + 库存」两张表。**事务变小了，但外面多了一次网络调用**——这个交换是拆微服务的本质代价，也是后面要做对账的原因。

## 六、这次没做什么（避免误读）

| 没做 | 原因 |
|---|---|
| 按域再分包（`com.dking.product.spu` / `.stock`） | 整个服务就是一个域，再分是过度设计 |
| DDD 聚合、领域事件 | 当前规模用不上，加进来只会让代码更难读 |
| 独立数据库 / 独立 schema | 还在过渡期，两个服务连同一个库；等交易迁移完再拆库 |
| 认证 | 依赖「8081 不对外 + 网关不转发 /internal」的网络隔离 |
| 写接口的调用方 | 第 2 步才做（订单改 Feign） |

## 七、下一步会改什么

第 2 步开始，`Shop` 这边会发生三件事：

1. 加 `ProductClient`（Feign），调上面的内部接口
2. `OrderServiceImpl` 的下单顺序变成 `reserve → 建单 → 失败 release`
3. `pay`、`cancel` 分别改调 `confirm`、`release`

到那时再回头看 `StockCommandService` 的 `decide()`，就能明白它防的每一种情况，都是从真实调用序列里来的。
