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
> 最后更新：2026-10-26

---

## 1. 技术栈与版本 ✅

| 组件 | 版本 / 选择 | 坑与备注 |
|------|------------|---------|
| JDK | 25（Dragonwell 25.0.4，路径 `C:\Users\14265\.jdks\dragonwell-ex-25.0.4`） | 命令行 Maven 默认 `JAVA_HOME` 指向 **JDK 17**，CLI 编译需临时指定 JDK 25；IDEA 内不受影响 |
| Spring Boot | 4.1.1 | Boot 4 默认 **Jackson 3**（包名 `tools.jackson`），网上 Boot 3 教程的 `com.fasterxml.jackson` 写法不适用 |
| MyBatis-Plus | `mybatis-plus-spring-boot4-starter` **3.5.18** | 必须用 boot4 starter，普通 starter 自动配置失效 |
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

## 6. 仓库与环境 ✅

- **MySQL 数据库名：`doro_mall`**（application.yaml 的 JDBC URL 已指向它）
- 远程：`git@github.com:DrivingWithFakeWine/mall.git`（SSH 已验证）
- 仓库本地身份：`DrivingWithFakeWine` / `DrivingWithFakeWine@users.noreply.github.com`（全局仍是 Gitee 身份，勿混用）
- 忽略：`Shop/target/`、`shop-web/node_modules/`、`shop-web/dist/`、**`application-local.yaml`（含数据库密码）**、`.idea/`、`.claude/`
- 占位模板 `application-local.example.yaml` 入库，新人复制后填密码
- SQL 脚本统一放项目根 `sql/` 目录，作为项目资产
