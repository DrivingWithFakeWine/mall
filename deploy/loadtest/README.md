# DoroShop 秒杀压测 Runbook

对应 `HANDOFF.md` 的压测目标与成功标准。所有命令在**压测机**执行，除非注明“应用机”。

> 两条硬规则：
> 1. **吞吐基线必须关闭 JFR 与 GC 文件日志**（`docker-compose.yml` 已默认关闭）；剖析用 `docker-compose.profiling.yml`，结果不与基线对比。
> 2. 每轮正式测量前，应用需预热约 **10～12 分钟**（约 6 轮 × 120s 的 50 线程商品列表），否则 QPS 会被 JIT 拉低到 130～250。
>
> 2026-11-05 的 Redis 对照计划见 `PLAN-2026-11-05-redis-vs-baseline.md`。

## 0. 前置检查

部署本次改动后，确认：

```bash
# 应用机：Nginx 已输出耗时字段（应能看到 $request_time 与 upstream 时间）
docker logs --tail 5 doroshop-nginx

# 应用机：Nginx stub_status（仅本机可访问）
curl -s http://127.0.0.1:8081/nginx_status

# 应用机：Actuator metrics（需要登录 token）
TOKEN=<任意测试用户 token>
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1/actuator/metrics/tomcat.threads.busy
```

JFR 在容器启动参数中已开启，文件为容器内 `/tmp/app.jfr`，测完取回：

```bash
docker cp doroshop-backend:/tmp/app.jfr ./app-$(date +%m%d-%H%M).jfr
```

> JFR 与 Actuator 会带来少量开销；全部压测结束后可从 `deploy/docker-compose.yml` 移除 JFR 参数并重启。

## 1. 准备测试数据

活动（库存 10 一个，库存 500 五个）在应用机执行：

```bash
mysql -h <RDS_ENDPOINT> -u <RDS_USER> -p doro_mall < seed-activities.sql
```

压测用户与 token（在能访问前端的机器执行，脚本可重复运行）：

```bash
node gen-users.mjs --base http://<APP_IP> --count 500 --out users.csv
scp users.csv loadtest:/opt/loadtest/users.csv
```

> 500 个用户够 S1/S2/S4 单轮使用；每轮之间必须执行 `reset-test-data.sql` 重置，否则同一用户不能再次抢同一活动。

## 2. 场景命令

通用参数：`-Jhost=<APP_PRIVATE_IP> -Jport=80`。结果目录建议 `/opt/loadtest/results` 与 `/opt/loadtest/reports`。

### 预热（每轮正式测量前）

用商品列表连续跑 6 轮 × 120s，直到 QPS 稳定（本机实测约 12 分钟后从 130 升到 400 左右）：

```bash
for r in 1 2 3 4 5 6; do
  jmeter -n -t plans/doroshop.jmx -l results/warm-$r.jtl -e -o reports/warm-$r \
    -Jhost=<APP_PRIVATE_IP> -Jport=80 -Jthreads=50 -Jramp=10 -Jduration=120 \
    -Jpath='/api/product/list?page=1&size=10'
done
```

### JFR 剖析（可选，单独一轮，不与基线对比）

```bash
# 应用机
docker compose -f docker-compose.yml -f docker-compose.profiling.yml up -d --force-recreate backend
# 压测机：预热后跑 30～60s，然后应用机停止 backend 以落盘
docker compose -f docker-compose.yml -f docker-compose.profiling.yml stop backend
docker cp doroshop-backend:/tmp/app.jfr ./app-profile.jfr
docker compose up -d backend     # 恢复无 JFR 的基线配置
```

### S0 回归基线（50 线程，商品列表）

```bash
jmeter -n -t plans/doroshop.jmx -l results/s0.jtl -e -o reports/s0 \
  -Jhost=<APP_PRIVATE_IP> -Jport=80 -Jthreads=50 -Jramp=10 -Jduration=180 \
  -Jpath='/api/product/list?page=1&size=10'
```

### S1 秒杀争抢（库存 10，500 线程突发）

```bash
jmeter -n -t plans/seckill-burst.jmx -l results/s1.jtl -e -o reports/s1 \
  -Jhost=<APP_PRIVATE_IP> -Jport=80 -Jthreads=500 -Jramp=1 \
  -JactivityId=3900000000000000001 -JusersFile=/opt/loadtest/users.csv
```

### S2 秒杀容量（库存 500，分 100/300/500 线程）

每轮换活动 id（`3900000000000000002` ~ `3900000000000000006`），或先执行重置脚本：

```bash
jmeter -n -t plans/seckill-burst.jmx -l results/s2-300.jtl -e -o reports/s2-300 \
  -Jhost=<APP_PRIVATE_IP> -Jport=80 -Jthreads=300 -Jramp=1 \
  -JactivityId=3900000000000000003 -JusersFile=/opt/loadtest/users.csv
```

### S3 纯非热点（商品列表/详情，100 线程）

```bash
jmeter -n -t plans/cold-browse.jmx -l results/s3.jtl -e -o reports/s3 \
  -Jhost=<APP_PRIVATE_IP> -Jport=80 -Jthreads=100 -Jramp=10 -Jduration=180
```

### S4 混合（80% 秒杀 + 20% 浏览，可改 90/10）

先重置数据，再执行：

```bash
jmeter -n -t plans/mixed.jmx -l results/s4-80-20.jtl -e -o reports/s4-80-20 \
  -Jhost=<APP_PRIVATE_IP> -Jport=80 -JseckillThreads=400 -JcoldThreads=100 \
  -JactivityId=3900000000000000004 -JusersFile=/opt/loadtest/users.csv -Jduration=240
```

90/10 版本：`-JseckillThreads=450 -JcoldThreads=50`。

## 3. 采集（与应用机/压测机上的压测并行执行）

应用机（需要 RDS 账号）：

```bash
DB_HOST=<RDS_ENDPOINT> DB_USER=<RDS_USER> DB_PASS=<RDS_PASS> DB_NAME=doro_mall \
  ./sample-mysql.sh 240 5 mysql-s4.csv
```

应用机（Actuator，TOKEN 必须是能访问该应用的登录用户）：

```bash
TOKEN=<token> BASE=http://127.0.0.1 ./sample-app.sh 240 2 app-s4.csv
```

应用机（Nginx）：

```bash
./sample-nginx.sh 240 2 nginx-s4.csv
```

系统层：

```bash
# 应用机
docker stats --no-stream doroshop-backend
top -b -n 1
# 压测机
top -b -n 1
```

## 4. 每轮后校验

```sql
-- 成功订单数应等于库存，available_stock 应为 0
SELECT COUNT(*) AS orders FROM orders WHERE activity_id = 3900000000000000001;
SELECT available_stock, total_stock FROM seckill_activity WHERE id = 3900000000000000001;
SELECT user_id, COUNT(*) c FROM orders
WHERE activity_id = 3900000000000000001 GROUP BY user_id HAVING c > 1;
-- 秒杀不应改动商品库存
SELECT available_stock, locked_stock FROM product_stock WHERE sku_id = <SKU_ID>;
```

重置下一轮：

```bash
mysql -h <RDS_ENDPOINT> -u <RDS_USER> -p doro_mall < reset-test-data.sql
```

## 5. 判定

- 功能：订单数 = 库存、无超卖、无重复用户、商品库存未被改动；
- 容量拐点：线程翻倍 QPS 增幅 < 20% 且 p99 翻倍；
- 热点挤压：S4 非热点 p99 相比 S3 劣化 > 50% → 做热点隔离；
- 资源：应用 CPU < 85%、MySQL CPU < 60%、Hikari pending = 0、Tomcat busy < 90%；
- 产出：每个场景的 jtl/html、JFR、GC 日志、三类采样 CSV、结论与下一步决策。

## 6. 本次结果（2026-11-04）

> 2026-11-05 的 Redis 对照结果见 `HANDOFF.md` 第 4.5 节与 `DECISIONS.md` 第 7 节；本节保留 2026-11-04 基线。

完整分标签数据在 `loadtest-reports/2026-11-04_c90322a-dirty/summary.csv`（线程数、ramp、时长、活动 id、samples/errors/avg/p50/p95/p99/max）；S4 的 burst 窗口定义为测试开始后的前 15 秒。

| 场景 | 参数 | 结果 |
|---|---|---|
| S0 商品列表 | 50 线程 / ramp 10s / 180s | 432 QPS，avg 112ms，p99 244ms，0 错误 |
| S1 秒杀争抢 | 500 用户 / ramp 0 / 活动库存 10 | 8s 完成，10 单，无超卖；p50 5.2s，max 6.9s |
| S2 秒杀容量 | 100/300/500 线程 / 库存 500 | 236/372 单，64/128 个 500（连接池超时），无超卖 |
| S3 纯非热点 | 100 线程 / ramp 10s / 180s | 列表 p99 780ms，详情 p99 1667ms |
| S4 80/20 | 400 秒杀 + 100 冷 / 240s | burst 窗口冷 p99：列表 6.36s、详情 8.15s；之后 0.79s / 1.59s |
| S4 90/10 | 450 秒杀 + 50 冷 / 180s | burst 窗口冷 p99：列表 7.63s、详情 8.07s；之后 0.45s / 0.92s |

结论：活动单行锁 → 事务长时间占用连接 → Hikari(20)/Tomcat(200) 饱和；突发期间冷请求 p99 劣化 6～10 倍，热点隔离触发线已满足。下一步先做 Redis 预扣 + Lua。

## 7. 报告版本与归档

- 目录命名：`loadtest-reports/<YYYY-MM-DD>_<git短哈希>[-dirty]/`，`dirty` 表示测量时工作区有未提交改动；
- **每次下载都用脚本**，不要手工 scp 散落文件：

```bash
deploy/loadtest/archive-results.sh loadtest 2026-11-04 s0-final s1 s3 s4b s4c
```

- 脚本会创建版本化目录，并生成 `MANIFEST.md`，包含：代码版本/分支/工作区状态、本地 JAR sha256、归档时间、JMeter 版本、场景列表、测量口径，以及每个 JTL 的 sha256；
- 同一天需要保留多轮结果时，在 label 里加轮次，例如 `2026-11-04-r2`，避免覆盖；
- `loadtest-reports/` 已在 `.gitignore` 中，报告只保留在本地与压测机。
