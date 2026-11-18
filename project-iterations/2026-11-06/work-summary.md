# 2026-11-06 迭代工作整理

## 本迭代目标

在已上线的 Redis 闸门版本上，解决两个压测暴露的问题：

1. 全赢场景（库存≈请求量）Redis 无效，Hikari 打满、出现大量 HTTP 500；
2. 单活动行锁导致库存写入串行，500 单时行锁等待明显。

方案：RabbitMQ 异步下单 + 8 桶库存，完成部署后用同一套 JMeter 计划复测并与上一轮对比。

## 已完成事项

| 事项 | 结果 |
|---|---|
| 秒杀异步下单 | 下单只做“活动校验 + Redis 预扣 + 入队”，返回 `PENDING + token`；消费者并发 8 落库；结果写 Redis，前端轮询 |
| 消息幂等与失败处理 | `orders.order_token` 唯一索引；同 token 重投直接返回成功；永久失败回滚预扣并写 FAILED；暂时异常重试 3 次后进 DLQ |
| 库存分桶 | 活动库存拆成 8 行；随机起始桶 + 线性探测；取消/超时按订单记录的桶号回补 |
| 活动行降级 | `seckill_activity.available_stock` 改为展示缓存，对账任务每 5 分钟按桶汇总刷新；读库存走 Redis/桶汇总 |
| 数据库迁移 | `orders.order_token`、`seckill_stock_bucket`、`orders.stock_bucket` 已应用到 RDS |
| 部署 | 应用机新增 RabbitMQ 3.13 容器；`SHOP_SECKILL_ASYNC_ENABLED=true`；镜像经 daocloud 国内源拉取 |
| 压测 | 执行 S0/S3/S1/S1b/S2(100/300/500)/S4a/S4b/S4c，混合场景同步采样 |
| 文档 | README、架构图、HANDOFF 第 4.6 节、DECISIONS 第 10/11 节 |

## 实践与验证内容

**测试**

- `mvn test`：95 个测试全部通过，新增 4 个异步用例（建单成功、地址失败回滚、消息重投幂等、同步模式）。

**本地端到端（真实 RabbitMQ）**

| 场景 | 结果 |
|---|---|
| 正常下单 | PENDING → 约 0.4s 后 SUCCESS，含 orderId/orderNo/金额/截止时间 |
| 同一用户重复提交 | 业务错误“您已参与过该活动”，无重复订单 |
| 地址不存在 | PENDING → FAILED，Redis 库存与用户占位已回滚 |
| RabbitMQ 停机下单 | HTTP 503，预扣回滚（库存与占位恢复） |
| 取消订单 | DB 桶与 Redis 同步 +1，二次取消被拒 |

**分桶验证**

- 单笔订单命中桶 1，桶总和 10→9，Redis 9；取消后恢复。
- 19 个用户并发抢库存 50：19 单、桶总和 31、无超卖，分布在全部 8 个桶（2/1/1/3/2/1/3/6）。
- 初版用 `userId % N` 只落在 3 个桶（雪花 ID 低位相关），改为随机起始桶后分布正常。

**线上冒烟**

- 部署后线上 PENDING→SUCCESS 正常，订单桶号、桶汇总、Redis 库存三者一致。

## 产物

| 内容 | 位置 |
|---|---|
| 代码提交 | `1b49547`（异步下单）、`4234c8b`（分桶）、`e0a5a18`（README/架构图） |
| 压测数据 | 本地 `loadtest-reports/2026-11-06_1b49547-dirty/`（summary.csv + JTL + HTML + MANIFEST） |
| 压测计划 | 本目录 `loadtest-plan.md` |
| 压测结果 | 本目录 `loadtest-results.md` |

## 已知问题 / 下一步

- 异步链路还缺：publisher confirm、DLQ 消息的预扣对账、RabbitMQ 数据卷；
- S1 受理延迟 2.24s，仍未达 1s，剩余成本在 2C 机器上的 HTTP 处理本身；
- 分桶数变更需重建桶；普通商品库存未分桶；
- 压测数据仍保留在 RDS，清理方式见 `server-info/servers.md`。
