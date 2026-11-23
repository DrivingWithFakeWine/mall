# 2026-11-22 迭代工作整理

## 本迭代目标

商品域已经在 2026-11-18 拆成独立服务（`product-service:8081`），但订单链路还在**直接读写 `product_stock`**——两个进程各有一份 Mapper 读同一批表，属于拆分的过渡残留。

本轮把订单链路的库存读写全部改成调用商品服务：

1. 加 `ProductClient`（OpenFeign），对接商品服务的内部接口
2. 下单顺序改成 `reserve → 建单 → 失败 release`
3. `pay` / `cancel` 改调 `confirm` / `release`

## 已完成事项

| 事项 | 结果 |
|---|---|
| Feign 客户端 | `ProductClient` 五个方法：批量查 SKU、reserve、release、confirm、sales；`url` 可配 `shop.product.url` |
| 错误翻译 | `ProductClientErrorDecoder` 把商品服务的 HTTP 409 + 字符串错误码转成 `StockOpRejectedException`，上层不解析中文 message |
| 下单改造 | 事务外做「地址校验 + 购物车读取 + 商品快照 + 校验」，再远程 `reserve`，最后本地事务只写订单域四张表；事务失败补偿 `release` |
| 立即购买改造 | 同上，单 SKU 版本 |
| 支付改造 | 状态条件更新放短事务里先落库；`confirm` + `sales` 放在事务之后，失败只记 ERROR |
| 取消/超时取消改造 | 状态先落库，再 `release`；秒杀订单仍回补活动桶 + Redis |
| 测试替身 | `LocalProductClient`（`@Profile("test")`，放在 `src/test`）用本地 Mapper 实现同一接口；`ProductClientConfig` 加 `@Profile("!test")` 关闭 Feign |
| 配置 | `application.yaml` 新增 `shop.product.url`，默认 `http://localhost:8081` |

## 关键决策

**1. 为什么是「先 reserve 再建单」，而不是「先建单再扣库存」**

两种顺序都不超卖，差别在失败模式：

| 顺序 | 中间态 | 用户看到 |
|---|---|---|
| 先建单 | 有单无货 | 下单成功后订单被取消 |
| **先预占（本轮）** | 有货无单 | 直接提示库存不足 |

500 人抢 10 件时，前者会产生 490 个待取消订单，后者只有 10 个订单被创建。而且秒杀链路本来就是「先预占再建单」，普通订单反着来会出现两套语义。

**2. 远程调用不进本地事务**

下单的本地事务只覆盖 `orders / order_item / order_address / cart`。`reserve` 在事务之前，`release` 在事务失败之后。

理由：拿着数据库连接等网络响应会拖长事务、占满连接池；而且本地回滚也撤不回已经成功的远程调用，只能靠补偿。

**3. 支付/取消：状态是唯一真相，远程失败只告警**

状态条件更新先落库（短事务），然后才调 `confirm`/`release`/`sales`。远程失败只记 ERROR，不回滚状态、不返回失败。

- 回滚状态会和用户的感知冲突（订单已付却说失败）
- 失败窗口由**对账任务**兜底（本轮未实现，见「已知问题」）

**4. 测试为什么用本地实现而不是 mock**

`OrderSubmitTest` 是 100 线程抢 10 件库存、断言真实库存值的并发用例，mock 不了。
如果走 Feign，`mvn test` 就必须先起一个 product-service，测试不再自包含。

所以：`ProductClient` 是接口，生产用 Feign 实现，测试用 `LocalProductClient`（直接操作同一个测试库）。
`ProductClientConfig` 上的 `@Profile("!test")` 是必须的——Spring Cloud 会把每个 `@FeignClient` 注册成 primary bean，两个 primary 会导致 `NoUniqueBeanDefinitionException`。

## 验证内容

**单元/集成测试（21 个订单用例全绿）**

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `OrderSubmitTest` | 1 | 100 线程抢 10 件库存，零超卖 |
| `OrderDirectBuyTest` | 5 | 立即购买、库存不足、地址校验 |
| `OrderDuplicateSubmitTest` | 1 | 重复提交 |
| `OrderTimeoutCancelTest` | 7 | 超时取消、取消回补、支付与取消互斥 |
| `OrderControllerIntegrationTest` | 7 | HTTP 契约 |

**本地端到端（Shop:8080 + product-service:8081，真走 Feign）**

| 场景 | 结果 |
|---|---|
| 立即购买 2 件 | 200；库存 `93/2 → 91/4`；台账 `RESERVE:2` |
| 支付 | 200；库存 `91/4 → 91/2`（locked 回落、available 不变）；台账 `CONFIRM:2 + SALES:2`；销量 +2 |
| 下单 3 件后取消 | 库存 `91/2 → 88/5 → 91/2`；台账 `RESERVE:3 + RELEASE:3` |
| 买 95 件（可用 91） | 400「库存不足：iPhone 17」；库存不变、订单数不变 |

测试数据已清理，库存回到 `93/2`，台账 0 条。

## 产物

**本轮新增 / 修改的代码**

| 文件 | 类型 | 说明 |
|---|---|---|
| `Shop/pom.xml` | 修改 | 引入 Spring Cloud `2025.1.4` BOM 与 `spring-cloud-starter-openfeign` |
| `Shop/src/main/java/com/dking/shop/client/ProductClient.java` | 新增 | Feign 接口：batchSkus / reserve / release / confirm / sales |
| `Shop/src/main/java/com/dking/shop/client/ProductClientConfig.java` | 新增 | `@EnableFeignClients`，`@Profile("!test")` 避免与测试替身争 primary |
| `Shop/src/main/java/com/dking/shop/client/ProductClientErrorDecoder.java` | 新增 | HTTP 409 → `StockOpRejectedException` |
| `Shop/src/main/java/com/dking/shop/client/StockOpRejectedException.java` | 新增 | 带机器可读错误码，继承 `BusinessException` |
| `Shop/src/main/java/com/dking/shop/client/dto/*.java` | 新增 | 5 个契约 DTO：SkuSnapshot / StockItem / StockOpRequest / StockOpResult / StockErrorResponse |
| `Shop/src/main/java/com/dking/shop/service/impl/OrderServiceImpl.java` | 重写 | 下单、立即购买、支付、取消四条链路改走 RPC |
| `Shop/src/main/resources/application.yaml` | 修改 | 新增 `shop.product.url`（默认 `http://localhost:8081`） |
| `Shop/src/test/java/com/dking/shop/client/LocalProductClient.java` | 新增 | 测试替身，`@Profile("test")`，用本地 Mapper 实现同一接口 |
| `.gitignore` | 修改 | 忽略 `*.err` 运行日志与 `.dsh_tmp/` |

**本轮文档**

| 文件 | 说明 |
|---|---|
| `project-iterations/2026-11-22/work-summary.md` | 本文件 |
| `project-iterations/README.md` | 时间线补 4 行、当前状态、目录说明、下一步候选 |

**本轮依赖的既有契约（不是本轮改动）**

| 内容 | 来源 |
|---|---|
| `/internal/product/**` 六个接口 | 2026-11-18 商品服务拆分 |
| 库存幂等台账 `stock_op_log` 与四态状态机 | 2026-11-18 / 2026-11-21 |
| 库存预占模型与取舍说明 | `DECISIONS.md` 第 12 节 |

## 已知问题 / 下一步

- **对账任务未实现**（最大缺口）：需要扫「已取消/超时但没有 RELEASE」「已支付但没有 CONFIRM」「预占超时未建单」三类；对账口径见 DECISIONS 第 12 节
- **Shop 仍保留 `product_*` 实体和 Mapper**：购物车（`CartServiceImpl`）和秒杀（`SeckillServiceImpl`）还在直读，第 3 步清理
- **测试替身与生产实现语义不一致**：替身没有幂等台账和状态机，只保证「不会扣成负数」；真实分支要靠商品服务自己的测试覆盖
- **跨服务事务不可回滚**：下单失败时 `release` 也失败的话，库存会锁住直到对账，这是接受的代价
- **未做 schema 拆分**：两个服务仍连同一个 `doro_mall` 库

## 2026-11-23 补充：释放重试，以及一次超卖回归

**改动**

- 释放库存统一成 `releaseStockWithRetry`：最多 3 次，每次记 `attempt=n/3`，失败按 200ms / 400ms 退避，3 次都失败打 ERROR「等待对账」
- 业务拒绝（`ALREADY_RELEASED` / `NOT_RESERVED`）不重试——重试不会改变结果
- 删掉 `releaseStockQuietly`：它先试 1 次再进 retry，实际最多 4 次，且失败的前几次没有日志

**发现的 bug（测试抓到的）**

`submit` 里原本用 `catch (RuntimeException)` 做补偿释放。而 `reserveStock` 把「库存不足」转成了普通 `BusinessException`，
于是**库存不足的订单也走了补偿释放**，把同 SKU 其他订单锁定的库存放了出去。

表现：`OrderSubmitTest`（100 线程抢 10 件）报 **12 单成功**——超卖。

修复：`reserveStock` 保留 `StockOpRejectedException` 类型（只换用户可读的 message），
`submit` 对它单独一个 catch 分支——**确定失败，绝不补偿**；只有「结果未知」（超时/连接中断）才补偿。

修复后同一用例回到 **10 单**，21 个订单用例全绿。

**验证**

- 停掉商品服务后取消订单：日志出现 `attempt=1/3`、`2/3`、`3/3` 三次 WARN（间隔约 200ms、400ms），随后一条 ERROR「连续 3 次失败，等待对账」；取消接口仍返回 200，库存保持锁定（`91/4` 不回落）
- 测试替身 `LocalProductClient` 只保证「reserve 幂等」，不校验状态机——`OrderTimeoutCancelTest` 的订单是直接插库造的，
  没有预占记录，替身必须放行；「库存不足不该补偿」这条约束由 `OrderServiceImpl` 的异常分支保证
