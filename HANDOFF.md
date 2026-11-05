# 交接文档（HANDOFF）

> 新会话开始时依次读：`MEMORY.md` 索引 → `BLUEPRINT.md` 概要 → `DECISIONS.md` → 本文件。
> 最后更新：2026-11-05

## 一、当前阶段

项目主线：设计 → 单体开发 → 部署（已完成）→ 测试（已完成）→ 压测与性能优化（阶段完成）→ **订单超时取消 → 秒杀模块（下一步）** → Redis / MQ / 分布式演进。

## 二、已完成与关键结果

### 功能

- 用户 / 商品 / 分类 / 购物车 / 订单 / 地址后端接口，前端页面齐全；
- 订单超时自动取消：定时扫表 + 条件更新取消 + 库存回滚，7 个用例；
- 秒杀 MVP（后端）：活动库存条件扣减 + `orders.activity_id` 唯一键幂等/限购，7 个用例；
- 用户端补全：账户中心（资料/改密/退出）、独立地址管理页、商品详情「立即购买」；
- 商品与秒杀关键字搜索（LIKE 基线，匹配商品名/副标题/分类名），首页与秒杀页头部中间搜索框，关键字写入 URL；
- 压测准备（2026-11-04）：JFR 启动参数、Actuator metrics、Nginx 耗时日志与 stub_status、MySQL/应用/Nginx 采样脚本、秒杀突发/非热点/混合三份 JMeter 计划，runbook 见 `deploy/loadtest/README.md`；
- 压测执行（2026-11-04）：S0 商品列表 **432 QPS**（旧基线 395～415），S1 500 人抢 10 件无超卖，S2 暴露 Hikari/Tomcat 饱和，S4 证明热点期间冷请求 p99 劣化 6～10 倍；
- Redis 对照压测（2026-11-05）：超额认购场景冷请求 p99 从 6～8s 降到 0.66～1.86s（S4b/S4a），全赢场景（S2/S4c）仍打满 Hikari，证明闸门只在超额认购时有效；S0 397 QPS（基线 432，-8%）；详见第 4.5 节；
- Redis 秒杀预扣（2026-11-04）：Lua 闸门（库存+用户占位）在 DB 事务之前，DB 条件更新仍为最终真相；Redis 不可用返回 503；本地 Docker Redis，测试 91 个全绿；
- 秒杀活动详情缓存（2026-11-05）：只缓存活动/SPU/SKU 元数据，库存/phase/serverTime 实时计算；Redis 故障时 fail-open 回退 DB；
- Docker Compose + Nginx + RDS 部署已上线；
- 测试 91 个：并发、服务层、Controller 集成，`mvn test` 全绿；
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

### 1. 订单超时自动取消 ✅（2026-11-03 完成）

- `OrderTimeoutTask` 每 60s 取一批（默认 100）过期未支付订单，逐单事务调用 `OrderService.cancelExpiredOrder`；
- 条件更新 `WHERE status = 0 AND expire_time < NOW()`，与 `pay`（已补 `expire_time > NOW()`）在同一行锁上互斥；
- 库存释放抽取为 `releaseLockedStock`，用户取消与超时取消共用；
- 配置 `shop.order.timeout.*`（enabled / scan-interval-ms / initial-delay-ms / batch-size），测试 profile 关闭；
- 测试见 `OrderTimeoutCancelTest`；演进触发条件与多实例方案见 DECISIONS.md。

### 2. 秒杀模块主干（MVP）⬜ 后端完成，前端与压测未做

```text
活动列表/详情 → 秒杀下单（时间窗、限购、库存、幂等）
→ 待支付 → 支付 / 超时取消 → 释放库存
```

已完成（后端，2026-11-03）：

- `seckill_activity` 表 + `orders.activity_id` + `uk_activity_user (activity_id, user_id)`；
- `SeckillService`：列表 / 详情（phase、serverTime）/ 下单（条件扣库存，单事务）；
- 支付、取消、超时取消按 `activity_id` 分派库存来源；
- `GET /seckill/list`、`GET /seckill/{id}`、`POST /seckill/{id}/order`，OpenAPI 契约已更新；
- 测试 `SeckillOrderTest` 7 个用例；
- shop-web 秒杀主页面 `/seckill`（倒计时、库存进度、抢购弹窗）与首页头部入口；
- 增量迁移脚本 `Shop/sql/migration_2026-11-03_seckill.sql`（本地 `doro_mall` / `doro_mall_test` 已执行，**线上 RDS 尚未执行**）。

未完成：

- JMeter 秒杀场景与压测基线（100 / 300 / 500 线程），观察活动行锁、MySQL CPU、连接池；
- 管理端活动维护（shop-admin 尚未创建）。

后续阶段（由压测驱动）：Redis 预热库存 + Lua 原子扣减 → RabbitMQ 异步下单 → Redisson 分布式锁 / 网关限流 / 防刷 → 分库分表与微服务拆分。

### 3. 可选

- 关闭 Micrometer `http.server.requests` 的 observation/metrics，再做 50 线程复测；
- 推送本地 9 个提交；
- 把压测结论同步到 DECISIONS。

### 4. 压测执行 ✅（2026-11-04 完成）

环境：应用 2C4G + RDS，JMeter 在独立 2C4G。吞吐基线**关闭 JFR/GC 日志**，预热约 12 分钟后测量。完整参数与分标签数据见 `loadtest-reports/2026-11-04_c90322a-dirty/summary.csv`，原始 JTL 与 HTML 报告在同目录 `results/`、`reports/`（压测机 `/opt/loadtest/` 也有副本）。报告目录按 `日期_git版本` 命名，每次下载用 `deploy/loadtest/archive-results.sh`，会自动生成带版本、JAR 校验值和文件校验值的 `MANIFEST.md`。

| 场景 | 参数 | 结果 |
|---|---|---|
| S0 商品列表 | 50 线程，ramp 10s，180s | **432 QPS**，avg 112ms，p99 244ms，0 错误（旧基线 395～415） |
| S1 秒杀争抢 | 500 用户，ramp 0，每人 1 次，活动 001（库存 10） | 8s 完成；成功 10 单、库存 0、无重复用户；p50 5.2s，max 6.9s |
| S2 秒杀容量 | 100/300/500 线程，ramp 0，活动 002/003/004（各库存 500） | 100 线程：100 单、0 错误；300 线程：236 单 + 64 个 500；500 线程：372 单 + 128 个 500；均无超卖 |
| S3 纯非热点 | 100 线程，ramp 10s，180s，列表/详情随机 | 列表 p99 780ms、详情 p99 1667ms，0 错误 |
| S4 混合 80/20 | 400 秒杀 + 100 冷线程，240s，活动 005（库存 500） | 秒杀 400 次 / 48 个 500；突发窗口冷请求 p99：列表 6.36s、详情 8.15s；突发后回落到 0.79s / 1.59s |
| S4 混合 90/10 | 450 秒杀 + 50 冷线程，180s，活动 006（库存 500） | 秒杀 450 次 / 59 个 500；突发窗口冷请求 p99：列表 7.63s、详情 8.07s；突发后 0.45s / 0.92s |

结论：

- 秒杀热点行锁 + Hikari（20）+ Tomcat（200）形成瓶颈：S2 的 300/500 线程出现连接池超时 500，DB `row_lock_current_waits` 峰值 18，Tomcat busy 峰值 200，Hikari pending 峰值 179；
- **热点确实拖垮非热点**：突发期间冷请求 p99 劣化 6～10 倍，超过 50% 触发线；
- 下一步优先级：① Redis 预扣 + Lua（解决行锁与连接池占用）→ ② 热点隔离/限流（保护冷流量）→ ③ MQ 异步下单；
- JFR 剖析会显著拉低 2C 机器 QPS，已拆到 `docker-compose.profiling.yml`，只用于找热点，不与吞吐基线对比。

### 5. Redis 对照压测 ✅（2026-11-05 完成）

- 应用 ECS 已部署 Redis 版 jar（`Shop.jar` sha256 `ad2696f1…d0d0b`）+ `redis:7-alpine` 容器；`.env` 已加 `REDIS_PASSWORD`，Redis 仅容器网络访问、不持久化；
- 压测机 `loadtest` 指向新公网 IP；内网链路 50→17ms；
- **预热需要约 18 分钟**（12 分钟只有 274 QPS，18 分钟后 394～416 QPS），比上一轮记录的 12 分钟更长；
- 本轮新增低库存活动 007（库存 10）、008/009（各 50），已写入 `seed-activities.sql` 与 `reset-test-data.sql`；
- 完整分标签数据：`loadtest-reports/2026-11-05_bae08ff-dirty/summary.csv`。

| 场景 | 参数 | 结果 | 与上轮对比 |
|---|---|---|---|
| S0 商品列表 | 50 线程 / ramp 10s / 180s | **397 QPS**，avg 122ms，p99 314ms，0 错误 | 基线 432 QPS / 112ms / 244ms，QPS -8%（±10% 内） |
| S1 秒杀争抢 | 500 用户 / ramp 0 / 库存 10（001） | 9s 完成，10 单、库存 0、无重复；avg 6.29s，p50 6.51s，p99 7.53s，0 错误 | 基线 p50 5.2s / max 6.9s，**延迟未改善** |
| S1b 秒杀争抢加强 | 500 用户 / 库存 50（009） | 50 单、无重复；avg 4.03s，p50 4.49s，p99 5.45s，0 错误 | 新增场景 |
| S2 秒杀容量 | 100 线程 / 库存 500（002） | 100 单，0 错误，avg 1.93s，p99 3.20s | 基线 100 单 / 0 错误 / 1.87s，基本持平 |
| S2 秒杀容量 | 300 线程 / 库存 500（003） | 270 单 + **30 个 500**（Hikari 超时），avg 4.24s，p99 7.40s | 基线 236 单 + 64 个 500 |
| S2 秒杀容量 | 500 线程 / 库存 500（004） | 402 单 + **98 个 500**，avg 6.29s，p99 10.61s | 基线 372 单 + 128 个 500 |
| S3 纯非热点 | 100 线程 / ramp 10s / 180s | 列表 p99 631ms、详情 p99 1387ms，0 错误 | 基线 780ms / 1667ms |
| S4a 混合 80/20 超额 | 400 秒杀 + 100 冷 / 240s / 库存 **50**（008） | burst 冷请求 p99：列表 **1.34s**、详情 **1.86s**；之后 0.87s / 1.76s；秒杀 p99 4.55s，0 错误 | 基线同参场景库存为 500（全赢），不可直接比较；冷 p99 比基线 6.36s / 8.15s 低 4～5 倍 |
| S4b 混合 90/10 超额 | 450 秒杀 + 50 冷 / 180s / 库存 **10**（007） | burst 冷 p99：列表 **0.66s**、详情 **1.01s**；之后 0.48s / 1.00s；秒杀 p99 4.28s，8 个 500 | 相比 S3 冷基线 +5% / -27%，**在 50% 触发线内** |
| S4c 混合 80/20 全赢 | 400 秒杀 + 100 冷 / 240s / 库存 **500**（005） | burst 冷 p99：列表 **5.90s**、详情 **6.95s**；之后 0.84s / 1.65s；秒杀 305 单 + 95 个 500 | 基线 6.36s / 8.15s，**基本持平，证明全赢下 Redis 无效** |

结论：

- **超额认购时闸门有效**：S1b 只有 50 次 DB 事务（行锁等待 19，Hikari pending 0），S4b 突发期间冷 p99 只比 S3 高 5%，远优于基线 6～10 倍劣化；
- **全赢时闸门无效**：S2 300/500 与 S4c 仍然打满 Hikari（20 连接），出现 30/98/95 个 500，冷 p99 依旧 5.9～6.9s；这就是 MQ 异步下单 / 库存分桶的依据；
- **S1 未达到 p99 <1s**：闸门把 DB 事务从 500 降到 10，但输家仍要为地址/活动各做 1 次 DB 查询，且在 2C 机器上 500 并发时 Redis 客户端等待被放大。500ms 超时版本出现 418/500 个 503；改成 2s 后 0 个 503，但请求仍要 4～7s。**瓶颈已从 DB 行锁转移到闸门前的读取与客户端调度**，不是 Redis 服务端（服务端 10～100µs）；
- 功能全部正确：无超卖、无重复用户、取消只回补一次、Redis 停机返回 503 且 DB 不变、恢复后懒初始化成功、详情缓存 fail-open。

功能演练记录：

| 项目 | 结果 |
|---|---|
| Redis 停机时下单 | HTTP **503**（`秒杀服务暂不可用`），DB 库存 400 / 100 单均未变 |
| Redis 停机时详情 | HTTP **200**（活动详情缓存 fail-open 回退 DB） |
| Redis 恢复后下单 | HTTP 200，懒初始化成功，DB 与 Redis 库存一致 |
| 同用户 10 并发 | 1 成功 + 9 个“已参与过该活动”，无重复订单 |
| 取消订单 | DB 与 Redis 库存同时 +1（398→399），二次取消被拒，不能复购 |

## 五、已知问题与注意事项

- JVM 本地锁只适用于单实例：购物车、订单提交、地址默认地址；扩容后需要 Redis 分布式锁或原子 SQL。
- 超时取消目前单实例运行：条件更新保证多实例下也不会重复取消，但多实例会重复扫描，届时换 ShedLock 或延迟消息。
- 订单提交幂等目前靠用户锁 + `TransactionTemplate`（单实例）。
- `pay` / `cancel` 的重复请求语义是报错，不是幂等成功，前端体验需产品确认。
- 秒杀和超时取消必须与支付用条件更新互斥。
- 秒杀库存独立于 `product_stock`：活动库存不预扣商品库存，两套库存暂不做对账；上线前若要用真实商品库存需补预扣逻辑。
- 秒杀取消后不能再次购买（`uk_activity_user` 决定），产品语义已确认。
- 压测前应丢弃重启后的前 1～2 轮（JIT 预热明显）；本轮使用 240 秒 + 180 秒预热。
- 服务器 compose 与本地 `deploy/docker-compose.yml` 可能有差异；prod 日志级别已在 `application-prod.yaml` 设为 WARN。
- 压测报告在本地 `loadtest-reports/{baseline,postidx,caffeine,logoff,keepalive}`，对应 summary CSV 在同级目录。
- 本轮压测预热至少 10～12 分钟（约 6 轮 × 120s），否则 50 线程 QPS 只有 130～250；JFR/GC 日志不计入吞吐基线。
- 压测暴露：Hikari 20 + connectionTimeout 3000ms 在秒杀热点下会触发连接池超时（HTTP 500），是当前真实瓶颈之一。
- 压测期后端开启 JFR（约 1~2% 开销）与 Actuator metrics；结束后可从 compose 移除 JFR 参数。Nginx 新增耗时日志与 8081 stub_status（仅本机/容器网段可访问）。
- Redis 只做秒杀闸门且**不持久化**：重启后从 MySQL 库存重新初始化；用户占位丢失由 `uk_activity_user` 兜底；Redis 不可用时秒杀接口返回 503，不降级 DB。
- 秒杀闸门的 Redis 超时已从 500ms 调到 2s：2C 机器上 500 并发时客户端等待会超过 500ms，旧值导致 418/500 个 503（2026-11-05 实测）；调到 2s 后不再误判，但 S1 单请求仍要 4～7s，瓶颈在闸门前的地址/活动 DB 查询与 Lettuce 单连接调度，下一步考虑连接池或把读取移到闸门之后。
- 本地跑测试前需要 Redis：`docker start doroshop-redis`（首次按 `application-local.example.yaml` 注释创建容器），测试使用 Redis DB 1。
- 活动详情缓存 TTL 30 秒 + 0～10 秒抖动，活动名/价格/时间最多有约 40 秒展示延迟；改变活动配置目前只能等 TTL（管理端上线后需在更新时 evict）。
- 账户：退出只清前端 token；改密后旧 token 仍有效至过期（JWT 无状态，未做吊销）。
- 搜索目前是 LIKE 基线（无法用索引），匹配商品名/副标题/分类名（命中一级分类会带出子分类，如“手机”）；商品量到几万、搜索 p99 > 200ms 或 MySQL CPU 持续 > 60% 时升级 FULLTEXT ngram；需要相关度/同义词/纠错时再上 ES（需单独节点）。

## 六、常用命令

```text
# 本地构建（JDK 25）
export JAVA_HOME='C:\Users\14265\.jdks\dragonwell-ex-25.0.4'
cd Shop && mvn clean package -Pprod -DskipTests

# 部署
scp -i ~/.ssh/loadtest.pem Shop/target/Shop-0.0.1-SNAPSHOT.jar doro:/opt/doroshop/backend/Shop.jar
ssh -i ~/.ssh/loadtest.pem doro "cd /opt/doroshop && docker compose restart backend"

# 全量测试（需要本地 MySQL doro_mall_test 与 Redis）
cd Shop && mvn test

# 本地 Redis（首次创建见 application-local.example.yaml）
docker start doroshop-redis

# 压测（压测机）
ssh -i ~/.ssh/loadtest.pem loadtest
cd /opt/loadtest && /opt/apache-jmeter-5.6.3/bin/jmeter -n -t plans/doroshop.jmx \
  -l results/xxx.jtl -e -o reports/xxx \
  -Jhost=<APP_PRIVATE_IP> -Jport=80 -Jthreads=50 -Jramp=10 -Jduration=180
```

## 七、未完成

- 秒杀模块：后端与前端主页面完成；管理端、压测基线未做；
- 秒杀迁移脚本：线上 RDS 已执行（2026-11-04）；
- Redis 预扣后的超额认购复测：已完成（2026-11-05），结果见第 4.5 节；
- 秒杀闸门性能：S1 仍未达 p99 <1s，需要降低闸门前读取开销或解决 Lettuce 单连接在高并发下的客户端延迟；
- 热点隔离、MQ 异步下单：未做；
- 订单履约（发货/确认收货）：随管理端一起做；
- Micrometer 指标实验：未做；
- 本地 9 个提交：未推送；
- 超时取消 / 秒杀改动：本地未提交；
- 压测报告：未入库。
