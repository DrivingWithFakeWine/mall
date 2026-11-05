# 2026-11-05 Redis 预扣对照压测计划

> 状态：✅ 已完成（2026-11-05）。结果见 `HANDOFF.md` 第 4.5 节、`DECISIONS.md` 第 7 节，产物在 `loadtest-reports/2026-11-05_bae08ff-dirty/`。
> 下一个窗口先读本文件，再读 `HANDOFF.md` 的“压测执行”和“下一次压测”两节。
> 本轮目的：验证 2026-11-04 压测暴露的问题，是否被 Redis 预扣解决，并与上一轮形成对照。

## 1. 本轮要回答的问题

1. **超额认购**（请求量远大于库存）时，Redis 闸门是否把 DB 事务从 500 次降到库存数量级，从而消除 Hikari/Tomcat/行锁饱和？
2. 热点突发期间，**冷请求 p99 劣化**是否从 6～10 倍回到 50% 以内？
3. **全赢场景**（库存≈请求量）Redis 是否仍然无效？如果是，这就是 MQ 异步下单/库存分桶的依据。
4. 功能正确性：无超卖、无重复用户、取消/超时只回补一次、Redis 故障返回 503 且恢复后库存一致。

## 2. 上一轮基线（2026-11-04，必须作为对照）

详细数据：`loadtest-reports/2026-11-04_c90322a-dirty/summary.csv`。

| 场景 | 参数 | 结果 |
|---|---|---|
| S0 商品列表 | 50 线程 / ramp 10s / 180s | 432 QPS，avg 112ms，p99 244ms，0 错误 |
| S1 秒杀争抢 | 500 用户 / ramp 0 / 库存 10 | 8s 完成，10 单；p50 5.2s，max 6.9s |
| S2 秒杀容量 | 100/300/500 线程 / 库存 500 | 236/372 单，64/128 个 500（连接池超时） |
| S3 纯非热点 | 100 线程 / 180s | 列表 p99 780ms，详情 p99 1667ms |
| S4 80/20 | 400 秒杀 + 100 冷 / 240s / 库存 500 | burst 冷 p99：列表 6.36s、详情 8.15s；之后 0.79s / 1.59s |
| S4 90/10 | 450 秒杀 + 50 冷 / 180s / 库存 500 | burst 冷 p99：列表 7.63s、详情 8.07s；之后 0.45s / 0.92s |

上一轮瓶颈：活动单行锁 → 事务长时间占用连接 → Hikari(20) / Tomcat(200) 饱和；冷请求被挤压。

## 3. 前置条件（应用端必须先部署 Redis 版本）

当前应用 ECS 上还是**没有 Redis 的旧包**；本地 Redis 代码已实现并通过 91 个测试，但尚未部署。

1. **先提交本轮 Redis 改动**（推荐），让报告版本号可追溯；若先不提交，`archive-results.sh` 会标成 `-dirty`。
2. 构建：`cd Shop && mvn -q -DskipTests package -Pprod`。
3. 应用 ECS 备份：`docker-compose.yml`、`backend/Shop.jar`。
4. `.env` 增加 `REDIS_PASSWORD=<强密码>`；上传新的 `deploy/docker-compose.yml`（含 redis 服务）。
5. `redis:7-alpine` 如拉取失败，按现有习惯走镜像站 `docker.1ms.run/library/redis:7-alpine` 后 `docker tag`。
6. `docker compose up -d --force-recreate backend redis`；确认 `docker ps` 中 backend/redis/nginx 正常。
7. 验证：
   - `curl -s http://127.0.0.1/api/seckill/list?page=1&size=1` 返回 200；
   - `docker exec doroshop-redis redis-cli -a "$REDIS_PASSWORD" ping` 返回 PONG；
   - 秒杀详情能正常返回；Redis 中能看到 `doro:seckill:activity:*` 或懒初始化后的 stock key。
8. 压测机：SSH 主机 `loadtest` 已指向新的公网 IP（本地 `~/.ssh/config` 已更新）；上传 `deploy/jmeter/*.jmx` 与新的 `users.csv`。
9. 预热：每轮正式测量前跑约 **10～12 分钟**（6 轮 × 120s 商品列表），否则 QPS 只有 130～250；**JFR/GC 日志必须关闭**（基线 compose 默认关闭）。

## 4. 场景设计（与上一轮对照 + 新增超额认购）

### 4.1 保留的场景（同参数，直接对比）

| 编号 | 场景 | 参数 | 对比目的 |
|---|---|---|---|
| S0 | 商品列表 | 50 线程 / 180s | 确认 Redis 改动无回归（目标≈432 QPS） |
| S1 | 秒杀争抢 | 500 用户 / 库存 10 | 目标：p99 从 6.7s 降到 1s 内，失败在 Redis 被挡 |
| S2 | 秒杀全赢 | 100/300/500 线程 / 库存 500 | 预期 Redis 无效，仍会出现连接池超时；作为 MQ/分桶依据 |
| S3 | 纯非热点 | 100 线程 / 180s | 冷请求基线，用于 S4 对比 |
| S4c | 80/20 全赢 | 400 + 100 / 库存 500 | 与上一轮 S4 完全同参，验证“全赢时 Redis 不能救” |

### 4.2 新增：超额认购混合场景（本轮核心）

| 编号 | 场景 | 参数 | 预期 |
|---|---|---|---|
| S1b | 秒杀争抢加强 | 500 用户 / 库存 50 | 50 单，p99 明显下降 |
| S4a | 80/20 超额 | 400 秒杀 + 100 冷 / 库存 10 或 50 | burst 冷 p99 劣化 ≤50% |
| S4b | 90/10 超额 | 450 秒杀 + 50 冷 / 库存 10 或 50 | 同上，更极端 |
| S4d | 80/20 中等 | 400 + 100 / 库存 50 | 可选的中间点 |

> 为什么新增：上一轮 S4 的库存是 500、请求 400，所有人都是赢家，Redis 闸门不会减少 DB 事务。要验证闸门价值，必须让请求量远大于库存。

### 4.3 功能演练

- **Redis 故障**：压测机发起一次秒杀下单前，在应用机 `docker stop doroshop-redis`，请求应返回 **HTTP 503**，DB 库存不变；`docker start` 后下一次请求懒初始化成功。
- **同用户并发**：10 个线程使用同一个 token 抢同一活动，只允许 1 单成功（Redis DUPLICATE；Redis 重启后由 DB 唯一键兜底）。
- **取消/超时回补**：抢到一单后取消，确认 DB 与 Redis 库存同步 +1、用户占位保留、不能复购。

## 5. 数据准备

1. 复用 `deploy/loadtest/seed-activities.sql` 的 001（库存 10）与 002～006（库存 500）。
2. 为超额认购新增 3 个低库存活动（建议 id `3900000000000000007`～`009`，库存 10/50/50），或每轮执行重置脚本后复用 001。
3. 生成用户：`node deploy/loadtest/gen-users.mjs --base http://<APP_PUBLIC_IP> --count 500 --out users.csv`，上传到压测机 `/opt/loadtest/users.csv`。
4. 每轮之间执行 `reset-test-data.sql` 清理 `lt%` 用户订单并恢复活动库存；不同轮次尽量使用不同活动 id，绕开 `uk_activity_user`。
5. 拍照/记录 DB 终态：活动订单数、`available_stock`、Redis `stock`、重复用户数、`product_stock`。

## 6. 执行顺序

1. 部署并验证 Redis 版本；
2. seed 活动 + 生成用户 + 预热；
3. S0、S3（基线）；
4. S1、S1b；
5. S2（100/300/500）；
6. S4a、S4b、S4c（可选 S4d）；
7. Redis 故障演练 + 同用户并发/取消回补；
8. 清理测试订单，归档报告，更新 HANDOFF/DECISIONS。

### 命令模板（压测机）

```bash
# S0
jmeter -n -t plans/doroshop.jmx -l results/s0-redis.jtl -e -o reports/s0-redis \
  -Jhost=<APP_PRIVATE_IP> -Jport=80 -Jthreads=50 -Jramp=10 -Jduration=180 \
  -Jpath='/api/product/list?page=1&size=10'

# S1（库存 10）
jmeter -n -t plans/seckill-burst.jmx -l results/s1-redis.jtl -e -o reports/s1-redis \
  -Jhost=<APP_PRIVATE_IP> -Jport=80 -Jthreads=500 -Jramp=0 \
  -JactivityId=3900000000000000001 -JusersFile=/opt/loadtest/users.csv

# S1b（库存 50）
jmeter -n -t plans/seckill-burst.jmx -l results/s1b-redis.jtl -e -o reports/s1b-redis \
  -Jhost=<APP_PRIVATE_IP> -Jport=80 -Jthreads=500 -Jramp=0 \
  -JactivityId=<库存50活动id> -JusersFile=/opt/loadtest/users.csv

# S3 冷基线
jmeter -n -t plans/cold-browse.jmx -l results/s3-redis.jtl -e -o reports/s3-redis \
  -Jhost=<APP_PRIVATE_IP> -Jport=80 -Jthreads=100 -Jramp=10 -Jduration=180

# S4a 80/20 超额（库存 10 或 50）
jmeter -n -t plans/mixed.jmx -l results/s4a-redis.jtl -e -o reports/s4a-redis \
  -Jhost=<APP_PRIVATE_IP> -Jport=80 -JseckillThreads=400 -JcoldThreads=100 \
  -JactivityId=<低库存活动id> -JusersFile=/opt/loadtest/users.csv -Jduration=240 -Jramp=0

# S4b 90/10 超额
jmeter -n -t plans/mixed.jmx -l results/s4b-redis.jtl -e -o reports/s4b-redis \
  -Jhost=<APP_PRIVATE_IP> -Jport=80 -JseckillThreads=450 -JcoldThreads=50 \
  -JactivityId=<低库存活动id> -JusersFile=/opt/loadtest/users.csv -Jduration=180 -Jramp=0
```

采样（应用机，和压测并行）：

```bash
DB_HOST=<RDS_ENDPOINT> DB_USER=<RDS_USER> DB_PASS=<RDS_PASS> DB_NAME=doro_mall \
  ./loadtest/sample-mysql.sh 260 3 mysql-s4a.csv
TOKEN=<测试用户token> BASE=http://127.0.0.1 ./loadtest/sample-app.sh 260 3 app-s4a.csv
./loadtest/sample-nginx.sh 260 3 nginx-s4a.csv
```

Redis 采样（可选，补一个脚本或手工）：

```bash
docker exec doroshop-redis redis-cli -a "$REDIS_PASSWORD" info stats | grep -E 'keyspace_hits|keyspace_misses|total_commands'
docker exec doroshop-redis redis-cli -a "$REDIS_PASSWORD" info memory | grep used_memory_human
docker exec doroshop-redis redis-cli -a "$REDIS_PASSWORD" -n 0 keys 'doro:seckill:*'
```

## 7. 验收标准

| 维度 | 标准 |
|---|---|
| S0 回归 | QPS 与 432 差距 ≤10%，0 错误 |
| S1 | 成功 10 单、无超卖/重复；p99 <1s（上轮 6.7s） |
| S1b | 成功 50 单；p99 明显低于上轮同并发 |
| S2 全赢 | 允许出现连接池超时；若与上轮接近，记录为 MQ/分桶依据 |
| S4a/b 超额 | burst 窗口冷请求 p99 相比 S3 劣化 ≤50% |
| S4c 全赢 | 预期冷 p99 仍显著劣化，用于说明 Redis 不解决全赢 |
| 资源 | Hikari pending 峰值接近 0（超额场景）；DB 行锁等待接近 0；Redis 内存稳定 |
| 功能 | 无 5xx（除故意停 Redis 的 503）、无超卖、取消/超时只回补一次 |
| 故障 | 停 Redis 时下单 HTTP 503，DB 不变；恢复后懒初始化成功 |

## 8. 结果记录（必须做）

1. 下载报告并生成版本化归档：

```bash
deploy/loadtest/archive-results.sh loadtest 2026-11-05 \
  s0-redis s1-redis s1b-redis s2-100-redis s2-300-redis s2-500-redis \
  s3-redis s4a-redis s4b-redis s4c-redis
```

2. 在 `HANDOFF.md` 新增“2026-11-05 压测结果”小节，沿用 2026-11-04 的表格格式：**场景、参数、结果、与上轮对比**；
3. 把瓶颈结论和下一步（是否做热点隔离/MQ）写入 `DECISIONS.md` 第 7/8 节；
4. `loadtest-reports/<日期>_<git版本>/summary.csv` 保留分标签数据，`MANIFEST.md` 由脚本生成；
5. 记录 Redis 故障演练与恢复结果。

## 9. 额外建议补充的压测项

- **Redis 延迟**：压测时在应用机跑 `redis-cli --latency`，确认闸门本身不是新瓶颈；
- **10 分钟长稳**：观察 Redis 内存、活动 key TTL、连接数是否稳定；
- **秒杀详情接口**：新增一个 `GET /api/seckill/{id}` 的 100 线程场景，验证本轮活动详情缓存的收益（上一轮 S3 打的是普通商品详情，不能直接对比）；
- **同用户并发**：10 线程同一 token，验证 Redis 占位与 DB 唯一键双层防重；
- **Redis 重启**：重启后首个请求触发懒初始化；确认库存与 DB 一致、占位丢失由唯一键兜底。

## 10. 风险与注意事项

- 本轮会重启应用后端并新增 Redis 容器，属于线上变更：先备份 compose、jar；确认 `.env` 有 `REDIS_PASSWORD`；
- Redis 不持久化是刻意设计，重启后从 DB 重建；不要把 Redis 当库存真相；
- 压测前必须预热 10～12 分钟，JFR/GC 日志关闭；
- 报告里不要写真实 IP、RDS 地址或密码（仓库 public）；
- 当前本地 Docker 已关闭；应用 ECS 上的 Redis 容器才是本轮对象。
