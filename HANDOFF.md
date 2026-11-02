# 交接文档（HANDOFF）

> 新会话开始时依次读：`MEMORY.md` 索引 → `BLUEPRINT.md` 概要 → `DECISIONS.md` → 本文件。
> 最后更新：2026-10-31

## 一、当前所处阶段

项目主线：设计 → 单体开发 → **部署（已完成）** → **测试（进行中）** → 压测（已暂停）→ 分布式演进。
顺序已调整：**单元测试 → 集成测试 → 压测**。

## 二、已完成

| 模块 | 状态 |
|------|------|
| 用户 / 商品 / 购物车 / 订单 / 地址 | 后端 20 个接口全部完成并实测 |
| 前端页面 | 首页、详情、购物车、结算、订单详情/列表、登录注册 |
| 部署 | 已上线，Docker Compose + Nginx + RDS（见下） |
| 接口契约 | `api/openapi.yaml`（20 接口），`docs/api-reference.html` 已生成 |

## 三、环境信息

| 项 | 值 |
|----|-----|
| 应用 ECS | 公网 `<APP_ECS_PUBLIC_IP>`，私网 `<APP_ECS_PRIVATE_IP>`，2核4G |
| 压测 ECS | 公网 `<LOADTEST_ECS_PUBLIC_IP>`，私网 `<LOADTEST_ECS_PRIVATE_IP>`，2核4G，已装 wrk |
| RDS | `<RDS_ENDPOINT>:3306`，库 `doro_mall`，账号 `<RDS_USER>` |
| 访问地址 | `http://<APP_ECS_PUBLIC_IP>/`（无域名/HTTPS） |
| 服务器目录 | `/opt/doroshop/`（compose、jar、dist、nginx 配置、`.env`） |
| SSH 别名 | 本地 `~/.ssh/config` 已配 `ssh doro` / `ssh loadtest`，含 60s 保活 |

## 四、压测现状（已暂停）

已完成：

- 数据：RDS `product_spu` 共 **100004 条**（10 万条 `code LIKE 'GEN-%'`，前 200 条高销量制造热点）
- wrk 基线（商品列表）：并发 10 → 80 QPS / 125ms；并发 50 → 157 QPS / 305ms
- 分类树：并发 10 → 189 QPS / 54ms
- 发现：瓶颈是**应用 CPU**（docker stats 160%），静态文件经 Nginx 有 4288 QPS，DB 查询 <1ms
- 每请求约 10ms CPU，偏高，原因未定位（GC 日志与业务日志关闭实验已配置但未执行完）

JMeter 相关：

- 测试计划已生成：`deploy/jmeter/doroshop.jmx`（用 `-Jthreads -Jramp -Jduration -Jpath` 参数化）
- 压测机已装 wrk；JMeter 安装步骤已给（下载 → scp → 解压 → 装 JDK17）
- **JMeter 尚未安装和运行**

## 五、下一步任务（按顺序）

1. **并发测试：购物车加购丢更新**
   - 本地无 Docker Desktop（`docker version` 连接失败），Testcontainers 不可用 → 用本地 MySQL 建独立测试库
   - 目标：写 20 线程并发加购同一 SKU 的测试，断言 `quantity == 20`
   - 预期：**当前实现会失败**（`CartServiceImpl.add` 是读-改-写）
   - 然后改成原子更新（`SET quantity = quantity + n`，含上限与库存条件），再跑变绿
2. 并发测试：库存超卖（100 线程下单 10 件库存，只成功 10 次）
3. 并发测试：订单重复提交（当前会失败，幂等未实现）
4. 集成测试：Controller 层参数校验、401/404、响应结构
5. 恢复压测：先定位 10ms CPU 问题（GC 日志 / 关业务日志对照实验），再做 Redis 缓存前后对比

## 六、已知缺陷（均已记录在 DECISIONS.md 待定项）

| 缺陷 | 状态 |
|------|------|
| 购物车 add 读-改-写，并发丢更新 | 待修（先写失败测试复现） |
| 订单提交无幂等，重复提交生成多单 | 待修 |
| 订单超时不自劢取消（locked 库存不释放） | 留待 MQ/定时任务阶段 |
| 结算页地址选择只在确认页，购物车页无 | 设计如此，非缺陷 |

## 七、未提交的改动

```
M  .gitignore  DECISIONS.md  Shop/pom.xml  Shop/sql/schema.sql
M  SecurityConfig.java  DoroUserServiceImpl.java  application.yaml
D  JwtUtil.java（被 JwtProvider 取代）
M  Cart.tsx  OrderDetail.tsx  ProductDetail.tsx/css
?? deploy/  JwtProvider.java  application-prod.yaml
?? Shop/sql/gen_product_spu.sql（22MB，已加入 .gitignore，勿提交）
```

建议拆两次提交：`refactor(auth)` JWT 密钥外置；`feat(deploy)` 部署配置与生产 profile。前端数量限制修复可并入 deploy 或单独提交。

## 八、常用命令

```text
# 本地打包（排除本地密码配置）
cd Shop && mvn clean package -Pprod -DskipTests
cd shop-web && npm run build

# 更新部署
scp Shop/target/Shop-0.0.1-SNAPSHOT.jar root@<APP_ECS_PUBLIC_IP>:/opt/doroshop/backend/Shop.jar
scp -r shop-web/dist/* root@<APP_ECS_PUBLIC_IP>:/opt/doroshop/frontend/dist/
ssh doro "cd /opt/doroshop && docker compose restart backend"

# 看日志
ssh doro "cd /opt/doroshop && docker compose logs -f backend"
```
