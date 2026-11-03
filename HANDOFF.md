# 交接文档（HANDOFF）

> 新会话开始时依次读：`MEMORY.md` 索引 → `BLUEPRINT.md` 概要 → `DECISIONS.md` → 本文件。
> 最后更新：2026-11-03

## 一、当前阶段

项目主线：设计 → 单体开发 → 部署（已完成）→ 测试（已完成）→ 压测与性能优化（阶段完成）→ **订单超时取消 → 秒杀模块（下一步）** → Redis / MQ / 分布式演进。

## 二、已完成与关键结果

### 功能

- 用户 / 商品 / 分类 / 购物车 / 订单 / 地址后端接口，前端页面齐全；
- Docker Compose + Nginx + RDS 部署已上线；
- 测试 47 个：并发、服务层、Controller 集成，`mvn test` 全绿；
- 商品列表压测：50 线程从 **75.8 QPS 优化到 395～415 QPS**，平均延迟 641ms → 117～123ms。

### 性能优化链

同一场景：`GET /api/product/list?page=1&size=10`，50 线程，每轮 3 分钟。

| 阶段 | QPS | 平均延迟 | 关键改动 |
|---|---|---|---|
| 原始 | 75.8 | 641 ms | 全表扫描 + filesort |
| 加索引 | 195 | 249 ms | `product_spu` 增加 `idx_status_deleted_sales (status, deleted, sales DESC, id DESC)` |
| + Caffeine 总数缓存 | 224 | 217 ms | 分页 `searchCount=false`，任意页码共享总数 |
| + prod 日志 WARN | 380 | 128 ms | 关闭每请求 INFO 日志 |
| + Nginx upstream keepalive | 395～415 | 117～123 ms | `upstream backend keepalive 32` + `Connection ""` |

当前瓶颈：**backend CPU 约 1.5 核，宿主机 2 核打满；MySQL 基本空闲**（Threads_running=1）。

JFR 采样结论（417 秒、28MB，文件在应用机 `/tmp/app.jfr`）：

- 第一热点：Micrometer 每请求 HTTP 指标（`HashMap.getNode` 4.11%，来自 `DefaultMeterObservationHandler`）；
- 第二热点：Tomcat `NioEndpoint.setSocketOptions` + `Poller.addEvent`（约 4.5%，Nginx 建连开销）；
- GC 只有 181 次 Young GC，不是瓶颈；
- Nginx keepalive 已经解决建连问题，Nginx CPU 从 35%～40% 降到 17%～22%；
- 尚未做：关闭 `http.server.requests` 的 observation/metrics 复测。

### 提交

```text
a1a96b1 perf(nginx): 启用 upstream keepalive 复用后端连接
03bd2f7 chore: 忽略本地压测报告目录
1fa7f0d perf(product): 商品列表增加索引与总数缓存，关闭生产高频日志
```

本地 `main` 领先 `origin/main` 9 个提交，**尚未推送**。`loadtest-reports/` 是本地压测报告，已 gitignore，未入库。

## 三、环境

| 项 | 值 |
|---|---|
| 应用 ECS | `<APP_ECS>`，2C4G，Docker Compose `/opt/doroshop` |
| 压测 ECS | `<LOADTEST_ECS>`，2C4G，JMeter 5.6.3 + JDK 17 |
| RDS | `<RDS_ENDPOINT>`，库 `doro_mall` |
| 本地测试库 | `doro_mall_test`，`application-test.yaml` |
| SSH | 本地 `~/.ssh/config` 有 `doro` / `loadtest`；本次使用 `~/.ssh/loadtest.pem` |
| 压测机目录 | `/opt/apache-jmeter-5.6.3`，计划 `/opt/loadtest/plans/doroshop.jmx`，结果 `/opt/loadtest/results`、`reports` |
| 应用诊断 | 应用机装有 `mysql-client`；JFR 文件 `/tmp/app.jfr` |
| Nginx 备份 | `/opt/doroshop/nginx/conf.d/default.conf.bak-keepalive` |

注意：仓库是 public，文档里不要写真实 IP、RDS 地址、账号或密码。

## 四、下一步任务

### 1. 订单超时自动取消（先做）

现状：`pay` / `cancel` 都是条件更新，数据一致；但过期未支付订单不会自动取消，`locked_stock` 会一直占用。

建议实现：

- 定时任务（`@Scheduled`）扫描 `status = 待支付 AND expire_time < now()`；
- 条件更新订单为已取消，同时回滚库存：
  `available_stock = available_stock + n`、`locked_stock = locked_stock - n`；
- 订单更新用 `WHERE status = 待支付`，防止与支付并发重复处理；
- 给 `orders(status, expire_time)` 加索引；
- 分批处理（如每批 100 单），失败记录并重试；
- 单实例先用定时任务；后续可换 RabbitMQ 延迟消息或 Redis 过期 key；
- 测试：过期取消、已支付不取消、支付/取消并发互斥、库存只释放一次、重复执行幂等。

### 2. 秒杀模块主干（MVP）

最小闭环：

```text
活动列表/详情 → 秒杀下单（时间窗、限购、库存、幂等）
→ 待支付 → 支付 / 超时取消 → 释放库存
```

数据模型建议：

- `seckill_activity`：spu_id、sku_id、秒杀价、总库存、可用库存、每人限购、开始/结束时间、状态；
- 秒杀订单：复用 `orders` 加 `activity_id`，或单独 `seckill_order`；
- 唯一键 `(user_id, activity_id)` 兜底幂等和限购。

阶段 1 只用 MySQL：

- 条件扣库存：`UPDATE ... SET available_stock = available_stock - ? WHERE id = ? AND available_stock >= ?`；
- 事务内创建订单；
- 唯一键防重复提交；
- 并发测试：100 线程抢 10 件、同用户重复提交；
- 观察热点行锁、数据库 CPU、连接池。

后续阶段（由压测驱动）：

1. Redis 预热库存 + Lua 原子扣减 + 活动详情缓存；
2. RabbitMQ 异步下单削峰；
3. Redisson 分布式锁 / 网关限流 / 防刷；
4. 分库分表与微服务拆分。

### 3. 可选

- 关闭 Micrometer `http.server.requests` 的 observation/metrics，再做 50 线程复测；
- 推送本地 9 个提交；
- 把压测结论同步到 DECISIONS。

## 五、已知问题与注意事项

- JVM 本地锁只适用于单实例：购物车、订单提交、地址默认地址；扩容后需要 Redis 分布式锁或原子 SQL。
- 订单提交幂等目前靠用户锁 + `TransactionTemplate`（单实例）。
- `pay` / `cancel` 的重复请求语义是报错，不是幂等成功，前端体验需产品确认。
- 秒杀和超时取消必须与支付用条件更新互斥。
- 压测前应丢弃重启后的前 1～2 轮（JIT 预热明显）；本轮使用 240 秒 + 180 秒预热。
- 服务器 compose 与本地 `deploy/docker-compose.yml` 可能有差异；prod 日志级别已在 `application-prod.yaml` 设为 WARN。
- 压测报告在本地 `loadtest-reports/{baseline,postidx,caffeine,logoff,keepalive}`，对应 summary CSV 在同级目录。

## 六、常用命令

```text
# 本地构建（JDK 25）
export JAVA_HOME='C:\Users\14265\.jdks\dragonwell-ex-25.0.4'
cd Shop && mvn clean package -Pprod -DskipTests

# 部署
scp -i ~/.ssh/loadtest.pem Shop/target/Shop-0.0.1-SNAPSHOT.jar doro:/opt/doroshop/backend/Shop.jar
ssh -i ~/.ssh/loadtest.pem doro "cd /opt/doroshop && docker compose restart backend"

# 全量测试（需要本地 MySQL doro_mall_test）
cd Shop && mvn test

# 压测（压测机）
ssh -i ~/.ssh/loadtest.pem loadtest
cd /opt/loadtest && /opt/apache-jmeter-5.6.3/bin/jmeter -n -t plans/doroshop.jmx \
  -l results/xxx.jtl -e -o reports/xxx \
  -Jhost=<APP_PRIVATE_IP> -Jport=80 -Jthreads=50 -Jramp=10 -Jduration=180
```

## 七、未完成

- 订单超时自动取消：未开始；
- 秒杀模块：未开始；
- Micrometer 指标实验：未做；
- 本地 9 个提交：未推送；
- 压测报告：未入库。
