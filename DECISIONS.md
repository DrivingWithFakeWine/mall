# DoroShop 决策记录（DECISIONS）

> **什么时候必须读这份文件**（给未来的自己 / 接手的助手）：
> - 要新建或修改任何数据表、字段、枚举取值之前
> - 要调整认证方案（JWT / 权限）、依赖版本、部署方式之前
> - 开始一个新模块（商品 / 购物车 / 订单 / 秒杀 / 管理端）之前
> - 发现代码与 `BLUEPRINT.md` 不一致时——**以本文件为准**，它记录"已实际执行的决定"
>
> 一句话触发词：**改表、改架构、改契约、升依赖——先读 DECISIONS.md。**
>
> 状态标记：✅ 已执行 ｜ ⏳ 待决定
> 最后更新：2026-11-03

---

## 1. 技术栈与版本 ✅

| 组件 | 版本 / 选择 | 坑与备注 |
|------|------------|---------|
| JDK | 25（Dragonwell 25.0.4，路径 `C:\Users\14265\.jdks\dragonwell-ex-25.0.4`） | 命令行 Maven 默认 `JAVA_HOME` 指向 **JDK 17**，CLI 编译需临时指定 JDK 25；IDEA 内不受影响 |
| Spring Boot | 4.1.1 | Boot 4 默认 **Jackson 3**（包名 `tools.jackson`），网上 Boot 3 教程的 `com.fasterxml.jackson` 写法不适用 |
| MyBatis-Plus | `mybatis-plus-spring-boot4-starter` **3.5.18** + `mybatis-plus-jsqlparser` **3.5.18** | 必须用 boot4 starter；**3.5.9+ 起分页插件 `PaginationInnerInterceptor` 拆到 jsqlparser 模块**，不加依赖会编译报找不到符号 |
| springdoc | 3.2.0 | 3.x 才支持 Boot 4 |
| jjwt | 0.13.0（api/impl/jackson 三件套） | 密钥 ≥32 字符，否则 WeakKeyException |
| 前端 | React 19 + TS 6 + Vite 8 + AntD 6 + Axios + react-router-dom | |
| 构建 | Maven 3.9.x；npm（Node 24） | Maven 4 与 IDEA 不兼容；npm 的 `allow-scripts` 全局残留会报 EALLOWSCRIPTS，已清理 |

## 2. 认证与授权 ✅

- **签发**：jjwt 自写 `JwtUtil`；**校验**：Spring Security **OAuth2 Resource Server**（单体阶段即启用，微服务网关阶段延续）
- 签发与校验必须使用**同一 SECRET**（当前写在 `JwtUtil.SECRET`，阶段二挪入配置）
- token 只放必要 claim：`sub=userId`、`role`；nickname 等展示信息走 `GET /user/me`
- **RefreshToken 暂缓** ⏳：触发条件 = 遇到"token 过期体验差"或"需要踢人下线"时再做
- 全局 **Long → String** 序列化（`JacksonConfig`，Jackson 3 写法），防止雪花 ID 在 JS 端精度丢失
- 密码：BCrypt 哈希存储，`matches()` 比对，绝不明文/绝不入日志
- 登录失败统一话术"用户名或密码错误"，防撞库探测

## 3. 前后端契约 ✅

- **契约唯一真相：`api/openapi.yaml`（OpenAPI 3.0.3）**，入库并走 PR 评审；改动必须前后端同步。
- 前端类型由 `npm run api:types` 从契约生成到 `shop-web/src/api/schema.d.ts`，**不得手写与之冲突的类型**。
- 破坏性变更（删字段/改语义）走 `/v2` 或先新增字段过渡；新增可选字段视为兼容变更。
- 后端实现以契约为准，SpringDoc 的 `/v3/api-docs` 是运行时校验依据。

| 约定 | 内容 |
|------|------|
| 响应体 | `{code, message, data}`；200 成功，400 业务/校验错误，500 兜底 |
| 登录响应 | `data.accessToken`（字段名**不是** token）、`data.userId`（字符串）、`data.nickname` |
| 注册字段 | `username / nickname / password / telephone`（前端）→ 数据库列 `phone`，**转换在 Service 层** |
| 请求头 | `Authorization: Bearer <token>`（前端 axios 拦截器统一携带） |
| 开发代理 | 前端 `/api` → `http://localhost:8080`（vite.config.ts） |
| 接口文档 | `http://localhost:8080/swagger-ui.html` |

**用户账户与立即购买（2026-11-03 实现 ✅，本次未改表）**
- 用户接口已补入契约：`POST /user/register`、`POST /user/login`、`GET /user/me`、`PUT /user/profile`、`PUT /user/password`；`UserVO` 增加 `telephone`
- 资料修改的手机号：空串或 null 都表示清空（存 NULL，`uk_telephone` 允许多个 NULL，只允许一个空串）；冲突返回「手机号已被使用」
- 改密需校验旧密码；**JWT 无状态，改密后旧 token 仍有效直到过期**（未做 token 吊销，属已知取舍）
- 退出登录只清前端 token，不新增后端接口
- 立即购买：`POST /order/buy-now`，单 SKU 直购，不经购物车、不删除购物车项；数量 1-99，事务内条件扣库存并生成订单/明细/地址快照
- 前端路由：`/account`（资料/改密/快捷入口）、`/address`（地址增删改、设默认）；下单弹窗统一用 `AddressSelectModal` + `AddressFormModal`
- 登录态组件 `UserNav` 读取 `/user/me`，资料保存后通过 `user-profile-updated` 事件刷新头部昵称

**关键字搜索（2026-11-03 实现 ✅，阶段 1）**
- 商品：`GET /product/list?keyword=`，`name LIKE %kw% OR subtitle LIKE %kw% OR category_id IN (命中的分类)`，与分类筛选叠加；关键字存在时**绕过总数缓存**，交给数据库 COUNT
- 分类名也参与匹配：命中一级分类时自动带上其子分类（`ProductSearchSupport`），所以搜“手机”能搜到挂在「手机」分类下的 iPhone
- 秒杀：`GET /seckill/list?keyword=`，匹配活动名，或按商品名/副标题/分类名查到 spuId（上限 200）再回查活动
- 统一用 `LikeUtils.normalize` 去空白、限长 50、转义 `%`/`_`/`\`，防止通配符退化成全表匹配
- 前端 `SearchBar` 放在首页与秒杀页头部中间，关键字写入 URL `?keyword=`，可清空、可翻页、可与分类叠加
- 演进（按数据量/瓶颈触发）：LIKE 基线 → MySQL FULLTEXT ngram → ES；触发条件见 `HANDOFF.md`

## 4. 数据库设计 ✅（2026-10-26 确定）

**已定方向**
- 商品采用 **SPU + SKU 多规格**（`product_spu` = SPU，`product_sku` = 可售单元）
- **商家模型（2026-10-27 决定）**：阶段一**单商家**，多商家作为后续迭代。命名仍保留 SPU/SKU（`product_spu` / `product_sku`），避免将来改名
- **多商家迭代路径（届时执行）**：① 新建 `merchant` 表 ② `product_spu` 增加 `merchant_id` 并回填默认商家 ③ 唯一索引 `uk_code` → `uk(merchant_id, code)` ④ 订单按商家拆单（**订单表建表时就预留 `merchant_id`，成本为零，避免历史订单迁移**）
- **库存独立建表** `product_stock`（秒杀行锁粒度 + 免迁移）
- 分类为 **两级**（`category.parent_id`，0 = 一级）
- 购物车先用 **MySQL 表**，压测后再换 Redis（作为缓存演进案例）
- **下单对象是 SKU 不是 SPU**——订单项关联 `sku_id`

**表清单**：`category` / `product_spu` / `product_sku` / `product_stock` / `product_image` / `product_spec` / `product_spec_value` / `product_sku_spec`，后续 `cart` / `orders` / `order_item` / `user_address` / 秒杀系列；多商家迭代时再加 `merchant`

**商品模块表设计（2026-10-27 确认，含多商家调整）**
- 归属链：`product_spu` → `product_sku` → `product_stock`；商品挂**二级**分类（`product_spu.category_id`）；单商家阶段无 `merchant_id`
- `product_spu`：详情描述用大文本（`TEXT` ↔ Java `String`），**列表查询不得查该列**
- `product_spu`：冗余 `main_image`（主图 URL，列表页用）与 `price_min`（最低价，列表页用）；源头分别是 `product_image.is_main` 与 `product_sku.price`，**SKU 改价/上下架、主图变更时必须重算**
- `product_spu.status`：**0 = 草稿，1 = 上架，2 = 下架**（仅上架对 C 端可见；与用户表 status 语义无关，勿混用）
- 编码唯一性：单商家阶段 SPU 用 `uk_code`、SKU 用 `uk(spu_id, sku_code)`；多商家化后 SPU 改为 `uk(merchant_id, code)`；下单、库存一律只认 `id`
- `product_sku` 字段：`spu_id`、`sku_code`（唯一索引 `uk(spu_id, sku_code)`）、`spec_desc`（规格展示串，如"红色/128G"，**冗余，写入时由规格值生成**）、`image`（该 SKU 主图）、`price` + `original_price`（原价/划线价）、`status`（**1 上架 / 0 下架——草稿只属于 SPU 级**）、`sales`、`publish_time`（上架时间，可空）
- 规格采用**拆表**方案：`product_spec`（规格项，如颜色）× `product_spec_value`（规格值，如红色）× `product_sku_spec`（SKU ↔ 规格值关联）；规格项/值归属链 `spu → spec → spec_value`；关联表 `uk(sku_id, spec_value_id)` + `idx(spec_value_id)`（反向查"哪些 SKU 含红色"）；**关联表物理删除、不做逻辑删除**（改规格时删旧建新）；规格名/值唯一性由应用层校验（逻辑删除会让唯一索引残留）
- `product_image`：`spu_id`（必填，保底归属）+ `sku_id`（**可空：NULL = SPU 通用图/详情轮播；非空 = 该 SKU 专属图**）+ `url` + `sort` + `is_main`（各自作用域内仅一张，应用层保证）；`product_spu.main_image` 与 `product_sku.image` 均为本表 is_main 图的冗余快照
- `product_stock`：`sku_id` **唯一索引**；字段 `available_stock`（可用）+ `locked_stock`（锁定）+ `version`（乐观锁，由 MyBatis-Plus 乐观锁插件维护）；**不含发货地址**（发货地址属于商家/仓库维度，不属于库存表）
  状态机：下单 available -= n、locked += n；支付 locked -= n；取消/超时 locked -= n、available += n
- **多商家迭代时的连锁影响**（届时才处理）：① 下单按商家拆单 ② 商家端数据权限 ③ 商家账号模型。**订单表建表时先预留 `merchant_id` 列**，零成本规避历史订单迁移

**订单模块表设计（2026-10-29 确认）**
- `orders.status` 状态机：**0 待支付 / 1 已支付 / 2 已发货 / 3 已完成 / 4 已取消**（与商品/用户 status 语义无关）
- `orders.merchant_id` 单商家阶段固定 0，多商家时用于拆单（已预留）
- `orders.order_no` 展示用订单号，**唯一索引**；`expire_time` 用于超时取消（`idx_status_expire` 支撑扫描）
- `order_item` 存**快照**：商品名、规格、图片、下单单价——商品改价改名不影响历史订单
- `order_address` 为**不可变快照**（无 update_time / deleted），每单一条 `uk(order_id)`；用户改地址不影响历史订单
- 库存两阶段：下单 available−n / locked+n，支付 locked−n，取消或超时 available+n / locked−n
- 下单必须在一个事务内完成：校验购物车 → 条件扣库存（`WHERE available_stock >= n`）→ 建订单/明细/地址快照 → 删除已下单购物车项

**订单超时自动取消（2026-11-03 设计并实现 ✅）**
- 实现位置：`task/OrderTimeoutTask` 负责循环，`OrderServiceImpl.cancelExpiredOrder` 负责单笔事务；库存释放抽取为 `releaseLockedStock`，与用户取消共用
- 测试：`OrderTimeoutCancelTest` 7 个用例（过期取消、未过期、已支付、幂等、过期不可支付、并发取消只释放一次、扫描过滤与 limit）
- **方案**：单体阶段用 `@Scheduled` 定时扫表，不引入 MQ（分布式组件应由压测瓶颈驱动，而非提前堆砌）
- **扫描**：`status = 0 AND expire_time < NOW()`，按 `expire_time` 排序、`LIMIT batchSize`（默认 100），只查 `id`；已有 `idx_status_expire (status, expire_time)` 覆盖，无需改表
- **单轮只处理一批**，剩余留给下一轮（`fixedDelay` 默认 60s）：失败订单回滚后仍是 `status = 0`，单轮 `while` 扫到底会死循环
- **事务边界**：定时任务类只做循环，单笔取消的事务方法放 `OrderServiceImpl`，经接口调用走代理——同内调用 `@Transactional` 不生效
- **状态迁移**：`WHERE id = ? AND status = 0 AND expire_time < NOW()` 条件更新为已取消并写 `cancel_time`；`rows = 0` 视为已被支付/取消，正常跳过。条件更新天然幂等，库存只释放一次
- **库存释放**：与用户主动取消共用 `releaseLockedStock(orderId)`（`available + n`、`locked - n`，`WHERE locked_stock >= n`），影响 0 行抛异常回滚本单
- **与支付互斥**：`pay` 的条件更新补 `expire_time > NOW()`，让"是否过期"也在同一条 UPDATE 上原子判定，支付与超时取消在同一行锁上排队
- **用户主动取消**不加过期条件（过期未清扫的单仍允许用户自己取消）
- **日志**：每轮一条 INFO 汇总（候选/取消/失败），订单级异常 WARN 带 `orderNo`；prod 的 `com.dking.shop: warn` 会吞掉汇总，需给定时任务包单独设 info
- **多实例**：条件更新保证正确性；重复扫描只是浪费，扩容后再上 ShedLock 或延迟消息
- **演进触发条件**（满足任一即换 RabbitMQ 延迟队列 / Redis ZSET 到期集合）：单轮扫描耗时 > 间隔的一半，或过期订单积压持续 > 1000 单
- **秒杀扩展**：秒杀订单复用该取消方法时，`releaseLockedStock` 需按订单类型分派额外回滚活动库存，现在不提前塞入
- 其余约定见上方各条

**秒杀模块 MVP（2026-11-03 设计并实现 ✅，后端）**
- **数据模型**：新增 `seckill_activity`（`spu_id`/`sku_id`/`seckill_price`/`total_stock`/`available_stock`/`per_user_limit`/时间窗/`status`）；秒杀订单**复用 `orders`**，加 `activity_id`（普通单为 NULL）与 `UNIQUE KEY uk_activity_user (activity_id, user_id)`
- **唯一键同时承担幂等与限购**：MVP 每人每活动限 1 单；**取消后不能再次购买**（产品语义已确认）。后续要做「每人限 N 件」时改为独立计数表
- **库存独立**：活动库存不预扣 `product_stock`；支付只累加 `sku/spu.sales`，取消/超时只归还 `available_stock`（且不突破 `total_stock`）。库存对账留到后续阶段
- **防超卖**：单事务内 `UPDATE seckill_activity SET available_stock = available_stock - 1 WHERE id=? AND status=1 AND start_time<=NOW() AND end_time>NOW() AND available_stock>=1`；不加本地锁，唯一键负责同用户并发
- **订单号**：秒杀单用 `"SK" + 雪花 ID`，避免原「秒 14 位 + 4 位随机」在峰值下撞 `uk_order_no`
- **接口**：`GET /seckill/list`、`GET /seckill/{id}`（公开）、`POST /seckill/{id}/order`（登录）；phase 0 未开始 / 1 进行中 / 2 已结束 / 3 已售罄；详情返回 `serverTime` 供前端倒计时
- **分派点**：`pay`/`cancel`/`cancelExpiredOrder` 按 `activity_id` 区分库存来源（`releaseOrderStock`）
- **测试**：`SeckillOrderTest` 7 个用例（100 线程抢 10 件、同用户并发只成 1 单、未开始/结束/下线/售罄、支付只加销量、超时与用户取消归还活动库存、列表与详情）
- **压测演进触发条件**：活动行锁等待明显、MySQL CPU 持续 > 60%、加线程无吞吐提升 → Redis 预热 + Lua 扣减；下单事务写占比高 → MQ 异步下单；刷单 → 网关限流 + 防刷
- **前端**：`/seckill` 主页面（活动卡片、倒计时、抢购弹窗选地址）+ 首页头部醒目入口，2026-11-03 完成
- **未做**：压测基线、管理端活动维护

**全表通用规则**
- 主键：雪花 ID `BIGINT`，**无自增**；MyBatis-Plus 全局 `assign_id`
- 金额：`DECIMAL(10,2)` ↔ Java `BigDecimal`，**绝不用 double**
- 状态：`TINYINT` + 注释枚举含义
- 审计：`create_time` / `update_time`（自动填充）；逻辑删除 `deleted TINYINT DEFAULT 0`
- **不建物理外键**，只建索引（逻辑外键）
- 每表每列写 `COMMENT`；唯一索引用 `uk_` 前缀，普通索引用 `idx_`
- 订单需存**价格快照**（下单时价格），商品改价不影响历史订单

**用户表已有语义（`doro_user`）**
- 主键列名 `user_id`；`user_name` / `nick_name` 为驼峰对应的表列
- `telephone` 列存手机号（实体字段也叫 telephone）
- status / role 取值见下方（已定）

**用户状态与角色取值（2026-10-26 定，全项目统一）**
- `status`：**1 = 正常，0 = 封禁**
- `role`：**0 = 普通用户，1 = 管理员**

## 5. 待定事项 ⏳

1. `shop-admin` 管理端工程尚未创建
2. 商品详情是否拆独立表（当前留在 `product_spu`，靠列表查询不查该列来规避大字段）
3. **多商家迭代（后续阶段）**：`merchant` 表字段、商家账号模型（`doro_user.merchant_id` vs 独立账号体系）、商家后台工程
4. **已知并发缺陷（留待压测验证后修复）**：`CartServiceImpl.add` 使用"读-改-写"（selectOne + updateById），并发加购同一 SKU 会丢更新；`updateQuantity` 的库存校验存在 TOCTOU（可接受，下单为最终防线）。计划：压测阶段先复现数据不一致，再改为 SQL 原子累加作为改造案例
5. **订单提交幂等未实现**：`POST /order/submit` 目前只靠前端按钮 loading（异步状态无法挡住同一 tick 的两次点击）；网络重试也可能重复下单。计划：后续加幂等 token 或"同用户短时间唯一待支付订单"约束

## 6. 仓库与环境 ✅

- **部署方案（2026-10-30 决定）**：阿里云 **ECS 2C4G** + **RDS MySQL 8.0（同 VPC 内网）**；应用以 **Docker Compose** 部署（backend + nginx 两个容器），Nginx 托管前端静态资源并把 `/api` 反代到后端；使用域名 + HTTPS；数据库连接与 JWT 密钥通过环境变量注入
- **部署状态（2026-10-30 已上线）**：ECS 公网 IP `<APP_ECS_PUBLIC_IP>`，访问地址 `http://<APP_ECS_PUBLIC_IP>/`（暂无域名与 HTTPS，备案后补）；RDS 内网地址 `<RDS_ENDPOINT>`，库 `doro_mall`，账号 `<RDS_USER>`；数据库已导入 13 张表与测试数据
- **镜像拉取**：Docker Hub 直连不稳定，采用第三方镜像站显式拉取后 `docker tag` 为标准 tag（如 `docker pull docker.1ms.run/eclipse-temurin:25-jre`）
- **打包**：`mvn clean package -Pprod` 会排除 `application-local.yaml`，避免开发库密码进入发布物；生产 profile 关闭 SQL 日志
- **MySQL 数据库名：`doro_mall`**（application.yaml 的 JDBC URL 已指向它）
- 远程：`git@github.com:DrivingWithFakeWine/mall.git`（SSH 已验证）
- 仓库本地身份：`DrivingWithFakeWine` / `DrivingWithFakeWine@users.noreply.github.com`（全局仍是 Gitee 身份，勿混用）
- 忽略：`Shop/target/`、`shop-web/node_modules/`、`shop-web/dist/`、**`application-local.yaml`（含数据库密码）**、`.idea/`、`.claude/`
- 占位模板 `application-local.example.yaml` 入库，新人复制后填密码
- SQL 脚本统一放项目根 `sql/` 目录，作为项目资产

## 7. 压测结论（2026-11-04）✅

- **口径**：应用 2C4G + RDS，JMeter 独立 2C4G；吞吐基线**关闭 JFR 与 GC 日志**，预热约 12 分钟（约 6 轮 × 120s）后测量；否则 50 线程只有 130～250 QPS
- **S0 商品列表**：50 线程 **432 QPS**，avg 112ms，p99 244ms，0 错误（旧基线 395～415）
- **S1 秒杀争抢**：500 用户、ramp 0、每人 1 次抢库存 10，8 秒完成，成功 10 单、库存 0、无重复用户；p50 5.2s，max 6.9s
- **S2 秒杀容量**：库存 500，100/300/500 线程；100 线程全成功；300/500 线程分别成功 236/372，出现 64/128 个 HTTP 500，原因是 **Hikari 20 + connectionTimeout 3000ms** 被打满（Tomcat busy 峰值 200、Hikari pending 179、DB 行锁等待 18），无超卖
- **S3 纯非热点**：100 线程、ramp 10s、180s，列表/详情随机；列表 p99 780ms，详情 p99 1667ms
- **S4 混合（80/20、90/10）**：400+100 / 450+50 线程，240s / 180s；突发窗口内冷请求 p99 从 0.8s/1.6s 升到 6.36～7.63s / 8.07～8.15s，**劣化 6～10 倍**，超过 50% 触发线；突发结束后回落到 0.45～0.79s / 0.9～1.59s
- **决策**：优先做 **Redis 预扣 + Lua**（减少热点行锁与事务持连接时间），再做热点隔离/限流保护冷流量；MQ 异步下单放在之后
- **监控口径**：JFR 与 GC 文件日志会显著拉低 2C 机器 QPS，已拆到 `docker-compose.profiling.yml`，只用于找热点，不与吞吐基线混跑
- 线上 RDS 已执行秒杀迁移；压测数据脚本与 JMeter 计划在 `deploy/loadtest/`
- **压测产物**：本地 `loadtest-reports/2026-11-04_c90322a-dirty/`（`summary.csv` + `results/*.jtl` + `reports/*` HTML + `MANIFEST.md`），压测机 `/opt/loadtest/results|reports/` 保留原始副本；报告目录按 `日期_git版本[-dirty]` 命名
