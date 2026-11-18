# DoroShop 微服务拆分方案（v2：只拆现状，不加新功能）

> 替代 `microservice-plan.md` v1（v1 含品牌/售后/新营销等新功能，本次全部不做，落点见附录 A）。
> 状态：**方案，未动代码**。目标是把现有的 16 张表 / 28 个接口按域切开，功能与接口契约零变化。

## 〇、结论

**4 个业务服务 + 1 个网关。**

```
浏览器 → Nginx（不动）→ Gateway :9000（路由 + JWT 校验）
                          ├── user-service      :9101
                          ├── product-service   :9102
                          ├── trade-service     :9103
                          └── marketing-service :9104
```

## 一、怎么分

| 服务 | 拥有的表 | 现有接口 | 读/写特征 | 扩容画像 |
|---|---|---|---|---|
| **user** | `doro_user`、`user_address` | `/user/*` 5 个、`/address/*` 4 个 | 读多写少，登录态相关 | 低 |
| **product** | `category`、`product_spu`、`product_sku`、`product_stock`、`product_image`、`product_spec`、`product_spec_value`、`product_sku_spec` | `/product/*` 2 个、`/category/tree` 1 个 | 极热读；唯一热点写是库存 | 高（读）→ 可加只读副本 |
| **trade** | `cart`、`orders`、`order_item`、`order_address` | `/cart/*` 5 个、`/order/*` 7 个 | 事务重、写多 | 中（峰值下单） |
| **marketing** | `seckill_activity`、`seckill_stock_bucket` + Redis 键 + MQ | `/seckill/*` 4 个 | 瞬时突发 | 极高且突发 |

表数 2 + 8 + 4 + 2 = 16，接口 9 + 3 + 12 + 4 = 28，**无遗漏、无重叠**。

职责要点：
- **user** 持有密码哈希、签发 JWT（jjwt）；网关只做校验，不做签发。
- **product** 持有价格与库存，是「商品可售性」的唯一判定方。
- **trade** 是 `orders` 的**唯一写入方**。这条是后面所有设计的基石。
- **marketing** 只持有活动规则与活动库存，不写订单。

## 二、为什么是这 4 个

三条判据，缺一条就说明不该拆：

1. **数据所有权互斥**：每张表只有一个写入方，4 个域的表零重叠，拆完不需要跨库写。
2. **扩容画像不同**：这是本项目拆服务最硬的理由，而且有自己的数据支撑 —— 2026-11-04 压测里，秒杀突发把**商品列表的冷请求 p99 从 0.8s 拖到 6.36s / 8.15s**。同一个进程里，秒杀把 CPU 和 Hikari 吃光，无辜的商品浏览跟着陪葬。拆开后这是故障隔离，不是架构表演。
3. **变更频率不同**：活动规则改得最勤（每次压测都在调），商品目录次之，用户表几乎不动。分开后发版互不牵连。

## 三、哪些「不该拆」

| 候选 | 结论 | 理由 |
|---|---|---|
| 库存独立成 inventory | **不拆** | 只有 1 张表，却是最热读路径（商品详情要带库存）和最热写路径。独立出去等于给两端各加一跳网络。等做「门店 × SKU 分仓」时再拆，那时才有正当理由 |
| 购物车独立 | **不拆** | 1 张表、5 个接口，只在下单时用，跟着交易走 |
| 认证/鉴权独立 | **不拆** | 签发的就一个 `JwtProvider`；校验放网关。拆出来是纯负担 |
| 搜索独立 | **不拆** | 现在是 `LIKE` + 索引，没有 ES。上 ES 那天才是拆的理由 |
| 订单再拆「订单/履约」 | **不拆** | 没有发货、售后，履约域不存在 |

服务数超过 4 个，运维成本涨得比收益快 —— 尤其在 2C4G 上。

## 四、拆完必须改的 10 处

| # | 现状（单体里直接做） | 拆分后 | 方式 |
|---|---|---|---|
| 1 | `OrderServiceImpl` 直读 `spu / sku / stock` | 调 product 批量查 | Feign |
| 2 | `submit / submitDirect` 直接 `update product_stock` | `product.reserve(orderNo, items)` | Feign（幂等键=订单号） |
| 3 | `submit` 直读 `user_address` | 调 user 取地址 | Feign |
| 4 | `CartServiceImpl` 直读 `sku / stock` | 调 product | Feign + 短缓存 |
| 5 | `SeckillServiceImpl.doSubmit` 一事务扣桶 + 建单 | 扣桶归 marketing，建单归 trade | 同步调用 + 失败补偿 |
| 6 | `pay` 改 `sku.sales / spu.sales`、释放 `locked_stock` | product 消费 `order.paid` | MQ |
| 7 | `cancel` 回补 `locked_stock` / 秒杀桶 | product / marketing 消费 `order.cancelled` | MQ（幂等） |
| 8 | `getOrderResult` 缓存未命中回落查 `orders` | 调 trade 按 token 查 | Feign |
| 9 | `OrderTimeoutTask`、`SeckillStockTask` 的 `@Scheduled` | 分布式任务 / MQ 延迟消息 | 多实例下必须改 |
| 10 | `SeckillServiceImpl` 借 `ProductSearchSupport` 按关键字查 spu | 调 product | Feign + Caffeine |

第 9 条是隐形地雷：这两个 `@Scheduled` 在单实例下正确，一旦同服务起两个实例就会重复取消订单、重复对账。拆服务时必然多实例，所以必须一起改。

## 五、三处硬骨头

### A. 下单：一个本地事务变成跨服务

现状 `submit()` 一个事务覆盖 6 张表（地址 + 购物车 + 库存 + 订单 + 明细 + 地址快照）。拆开后 trade 的事务只剩自己的 4 张表，库存成了远程。

**方案：`reserve` - `release` 两段，不引入 Seata。**

关键简化：现有语义本来就是「下单锁库存（`locked+n`）、支付才扣（`locked-n`）」，所以 TCC 里的 **confirm 天然由支付事件承担**，不需要单独一次远程调用。

```
trade: 校验地址(user) → 批量查商品(product) → 逐项 reserve(product)
     → 本地事务：orders + order_item + order_address + 清购物车
     → 任一步失败：release 已 reserve 的项
```

- 幂等：`reserve / release` 以 `orderNo` 为业务键，product 侧加一张 `stock_op_log(order_no, sku_id, op)` 唯一索引做台账，重投不会重复扣/重复补。
- 兜底：对账任务扫「已取消/已超时、但 reservation 仍占用」的订单 → 补 release。失败模式的取向是**宁可少卖，不可超卖**。

### B. 取消与超时回补

现状是同一事务里改状态 + 回补库存，异常整体回滚。拆开后做不到原子。

**方案：订单状态是唯一真相，库存回补走事件 + 对账。**

```
trade: 条件更新 status=已取消（本地事务，成功即定论）
     → 发 order.cancelled（orderNo, activityId, bucketNo, items）
     → product 消费：普通单回补 locked_stock
     → marketing 消费：秒杀单回补桶 + Redis 占位
```

两侧都用 `orderNo` 幂等。消息丢失进 DLQ，由对账任务补。中间态（已取消未回补）只会导致暂时少卖，不会超卖。

### C. 秒杀建单：现在是一个事务，拆完必须是 saga

`doSubmit()` 现在一个事务里做「扣桶 + 插 orders + 插 order_item + 插 order_address」。桶在 marketing 库、订单在 trade 库，**这个事务必须拆开**。

```
1. marketing  /seckill/{id}/order
     Lua 闸门（库存 + 用户占位）→ 发 seckill.order.create → 写 PENDING → 返回 token   【不变】
2. trade 消费 seckill.order.create
     ① 按 token 查本地订单：已存在 → 直接写结果返回（重投幂等，不能重复扣桶）
     ② 校验地址(user)、校验商品(product)
     ③ 调 marketing.deductBucket(activityId, userId) → 拿到 bucketNo
     ④ 本地建单（orders/order_item/order_address）
     ⑤ 写成功结果
3. 永久失败（地址错/商品下架/已参与过）
     trade 调 marketing.restoreBucket + 释放 Redis 占位 → 写失败结果
4. 暂时失败（DB 抖动）→ 抛给 MQ 重试；桶不动
```

**顺序不能变**：先查订单、再扣桶。反过来，消息重投会重复扣桶。

这一步是整次拆分风险最高的地方 —— 它同时动了秒杀、订单、库存三块，而这套异步 + 分桶 + 幂等是项目最值钱的资产。**动代码前先补行为快照测试**（固定种子数据 + 并发断言：赢家数、库存、重复提交、取消只回补一次）。

## 六、数据怎么拆

一个 MySQL 实例、四个 schema，**先不分实例**：

| Schema | 内容 |
|---|---|
| `doro_user` | `doro_user`、`user_address` |
| `doro_product` | 商品域 8 张表 |
| `doro_trade` | `cart`、`orders`、`order_item`、`order_address` |
| `doro_marketing` | `seckill_activity`、`seckill_stock_bucket` |

- 不分实例的理由：跨域事务在应急时还能用本地事务兜底，给迁移期留退路；RDS 分 4 实例成本 ×4；这一步要解决的是「进程解耦 + 独立扩容」，不是「单库到顶」。
- 拆库后**禁止跨 schema JOIN**，所有跨域查询改成 Feign + 本地快照。
- `doro_trade` 里的 `order_item`、`order_address` 就是商品名、价格、收货人的**快照**，这是天然的解耦，不用改。

## 七、网关与契约：前端零改动

现在 Nginx 已经是 `/api/` → backend 且**剥掉前缀**（`deploy/nginx/default.conf`），前端 10 个 api 模块全用 `/api/...` 绝对路径。

所以只要把 Nginx 的 upstream 从 `backend:8080` 改成 `gateway:9000`，网关内部按第一段路径分流，**前端一行不用改，OpenAPI 契约不变**：

| 路径前缀 | 去往 |
|---|---|
| `/user/**`、`/address/**` | user |
| `/product/**`、`/category/**` | product |
| `/cart/**`、`/order/**` | trade |
| `/seckill/**` | marketing |

认证收敛在网关：校验 JWT 后透传 `X-User-Id` / `X-User-Role`，下游不再各自装一遍 Security（省 4 份配置，也避免 4 处实现漂移）。现在用的是 HS256 共享密钥，网关与 user 共享同一 `JWT_SECRET` 即可；有余力再换 RS256，网关只拿公钥。

## 八、落地顺序：代码一次切，部署分两批

**代码一次切 4 个模块**（Maven 多模块，域内聚），**部署按资源分批**（同一套代码，不同打包方式）。

| 步骤 | 内容 | 工期 | 验收 |
|---|---|---|---|
| 0 | Maven 多模块（parent / common / 4 服务 / gateway），**仍单进程部署** | 3~5 天 | 95 用例全绿；模块间零直接 import（`maven-enforcer` 卡反向依赖）；S0 数字不变 |
| 1 | 网关上线 + **product 独立** | 1 周 | Nginx 改指网关；S0 QPS ≥ 395、p99 不劣化 >20%；商品服务单独重启不拖垮交易 |
| 2 | **user 独立** | 3 天 | 网关做 JWT 校验、`X-User-Id` 透传；登录/地址/下单回归 |
| 3 | **trade 独立**（reserve/release + `order.paid`/`order.cancelled` + 定时任务改造） | 1.5~2 周 | 并发用例全过（超卖/同用户并发/重复提交/取消回补/异步重投）；预扣对账 0 差异 |
| 4 | **marketing 独立**（秒杀建单搬到 trade） | 1~1.5 周 | S2/S4 不低于 11-06 基线（突发冷 p99 0.71 / 1.20s）；取消只回补一次 |
| 5 | 治理与复测（traceId、Prometheus、docker compose 一键起） | 3~5 天 | 每步 before/after 数字进文档 |

合计 **5~6 周**（业余 10~15h/周）。

顺序的理由：**商品先行**（读最热、无状态、表最成熟，出事最好回滚）；**交易在营销之前**（必须先让 trade 成为订单唯一写入方，marketing 才可能不再直接 insert orders）；营销最后，因为它同时依赖前三者。

**部署分批**（关键，见下节资源账）：

| 批次 | 进程 | 条件 |
|---|---|---|
| 1 | 单体（user+trade+product）+ **marketing 独立** + 网关 | 2C4G 就能跑（2~3 个 JVM） |
| 2 | + product 独立 | 4C8G 或 2 台 2C4G |
| 3 | + user、trade 独立 | 4C8G 或 2 台 2C4G |

批次 1 就能拿到这次拆分 80% 的收益：**秒杀再突发，也不会把商品浏览拖到 6.4s**。这也是 2026-11-07 handoff 里「只做一个最小拆分」的落点，两者并不冲突。

## 九、资源账（必须先算）

现状：2C4G 一台，backend `-Xmx1g` / `mem_limit 1600m`，Redis 320m，Rabbit 320m —— 已经吃掉大半内存，2 核也被 backend 占满。

5 个 JVM 的账：gateway ~250M + 4 × 400M ≈ **1.85G**，加中间件 640M ≈ **2.5G**，还没算堆外、Metaspace、页缓存。内存勉强，**2 核跑 5 个 JVM（每个默认按核数开 GC 线程）是真正的瓶颈**，会频繁 GC 互相抢 CPU。

| 方案 | 配置 | 说明 |
|---|---|---|
| A | 4C8G 单机 | 5 服务都能跑，加 Nacos 就紧 |
| B | 2 台 2C4G | 网关+user+product / trade+marketing |
| C | **保持 2C4G，只拆 marketing** | 批次 1；代码还是 4 模块，只是打包成一个进程 |
| D | 不起注册中心 | 服务发现用 Nginx upstream 静态写死，省 500M 和一个故障点 |

建议：**C 起步，D 常驻**。目标是「拆分 + 独立扩容」的话，注册中心不是必需品，Nginx 静态 upstream 足够；等真要动态扩缩容再加 Nacos。

## 十、风险

1. **秒杀链路是最高风险点**（§5.C）。拆它之前先写行为快照测试，用数据证明「拆前拆后行为一致」，再拆。
2. **测试基线会失效**：95 个用例强依赖真实 MySQL/Redis，拆库后库结构全变。不先上 Testcontainers，每步都无法证明没退化。
3. **资源不足**：2 核跑 5 个 JVM 的 GC 抖动，可能让所有性能数字倒退，看起来像「拆坏了」。
4. **雪花 ID 冲突**：MyBatis-Plus `assign_id` 按机器派生 workerId，多实例可能撞号。拆之前显式配 `workerId`，或改用号段服务。
5. **多实例定时任务**：`@Scheduled` 两处（订单超时、秒杀对账）必须与拆分同步改成分布式执行。
6. **调试体验退化**：一个请求跨 4 个服务，没有 traceId 就是灾难。步骤 1 就要把 traceId 写进 MDC，别等治理阶段。
7. **只为拆而拆**：这套功能在单机上跑得挺好，拆的动力必须是「隔离 + 独立扩容」，要在文档里写清楚，否则就是简历工程。每一步都要有 before/after 数字，性能变差也要能解释。

## 附录 A：本次不做（原括号内容）的落点

| 内容 | 未来落点 |
|---|---|
| 商品品牌 | product 服务加 `brand` 表 + `spu.brand_id` |
| 商品属性字典 | product 服务加 `product_attr` 三张表，与现有 `product_spec` 边界写死（规格生成 SKU、属性只做筛选） |
| 售后退款 / 快递发货 / 门店自提 | trade 服务加 `after_sale` / `shipment` / `store`，并重写订单状态机 |
| 拼团 / 限时折扣 / 满减送 | marketing 服务加活动表；优惠计算收敛到一个试算接口 |
| 会员等级 | user 服务加 `member_level` / `user_member`，折扣仍由营销试算 |

这些都不影响本次拆分边界 —— 上面每个落点都落在已有的 4 个域里，说明这次切法是稳的。
