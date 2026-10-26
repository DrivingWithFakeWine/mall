# DoroShop 项目蓝图：从单体到分布式高并发

> 目标：完整走一遍真实项目生命周期 —— 设计 → 单体开发 → 部署 → 压测暴露瓶颈 → 分布式改造 → 高并发
> 方法论：每引入一个中间件，先压测记录基线 → 改造 → 再压测对比，用数据证明每步演进的价值

## 业务场景：在线商城（含秒杀）

| 模块 | 功能点 | 演进中的角色 |
|------|--------|--------------|
| 用户 | 注册/登录（JWT，`role` 字段区分管理员/普通用户）、收货地址 | 分布式 Session/Token、用户服务拆分 |
| 商品 | 列表/详情、分类、搜索 | 热点读 → Redis 缓存、ES 搜索（可选） |
| 购物车 | 加购、改数量、结算 | Redis 购物车存储（可选演进点） |
| 订单 | 下单、模拟支付、超时取消 | 长链路 → MQ 异步、分布式事务 |
| 库存 | 下单扣减、防超卖 | 乐观锁 → Redis 原子扣减 + 分布式锁 |
| 秒杀 | 限时活动、瞬时流量 | 削峰、限流、独立扩容的核心战场 |

## 阶段一：单体架构

**后端**
- Java 25 (JDK 25) + Spring Boot 4.1.1
- Spring MVC（RESTful API）
- MyBatis-Plus（ORM；Spring Boot 4 必须用专用 starter `com.baomidou:mybatis-plus-spring-boot4-starter`，版本 ≥ 3.5.16，勿用 boot3 版本）
- Spring Security + JWT（认证；2026-10-26 用户决定：**校验走 OAuth2 Resource Server**（spring-boot-starter-oauth2-resource-server），**签发仍用 jjwt**，单体阶段即采用，微服务网关阶段延续）
- SpringDoc OpenAPI（接口文档）
- Hibernate Validator（参数校验）
- JUnit 5 + Mockito（核心业务逻辑单测）

**数据库**：MySQL 8.0（订单/商品/库存表分离，库存表独立行锁粒度）

**前端（前后端分离，双前端工程，2026-10-23 确认）**
- 技术栈：React 19 + TypeScript 6 + Vite 8 + Ant Design 6 + Axios（React Router、Zustand 到多页面/全局态时再装）
- `shop-web` 用户端商城：浏览、购物车、下单、秒杀（2026-10-23 脚手架完成，已配 /api→8080 开发代理，构建通过）
- `shop-admin` 管理端：商品上下架、秒杀活动管理（对应用户表 `role` 字段：0 管理员 / 1 普通用户）
- 开发期前端走 Vite 代理调后端；部署期 Nginx 统一反代（阶段二）

**工程环境记录（2026-10-23 已搭建，Spring Boot 已启动成功）**
- IDEA 2026.1.2 + JDK 25
- 构建工具：Maven 3.9.x（注意：Maven 4.x 与 IDEA 集成不兼容；settings.xml 配阿里云镜像防依赖下载断流）
- Initializr 勾选依赖：Spring Web、Spring Security、MySQL Driver、Validation、Lombok、Spring Boot Actuator
- 手动引入的依赖：MyBatis-Plus（boot4 starter）、SpringDoc OpenAPI、jjwt
- 刻意不选：OAuth2 Client、OAuth2 Resource Server（JWT 单体走 jjwt 路线，Resource Server 留到微服务网关阶段）；Spring Data Redis、RabbitMQ 等到压测暴露瓶颈后再引入
- 启动日志出现 "Using generated security password" 属正常：默认 Basic 认证兜底，写好 SecurityFilterChain 后自动消失

**架构原则（单体阶段就要做对的）**
- Controller → Service → Mapper 三层，业务不写在 Controller
- 统一响应体、统一异常处理（@RestControllerAdvice）
- 面向接口编程，为后续拆服务留缝
- **每完成一个模块的业务代码，补充关键业务日志**（@Slf4j：成功 info / 可疑失败 warn / 异常 error 进全局处理器；`{}` 占位符；密码、token、手机号等敏感信息绝不入日志）

## 阶段二：部署

- Docker + Docker Compose：本地一键起 MySQL + 后端 + Nginx
- Nginx：前端静态资源托管 + 后端反向代理
- Linux 云服务器部署
- 可选：GitHub Actions 自动化 CI/CD

## 阶段三：压测与瓶颈发现

- JMeter 为主（阶梯加压），wrk 辅助对比
- 压测场景：商品列表、商品详情、下单、秒杀
- 可观测性：
  - Spring Boot Actuator + Micrometer + Prometheus + Grafana
  - Arthas（线程、热点方法、慢 SQL 诊断）
  - MySQL 慢查询日志 + explain

## 阶段四：分布式演进（瓶颈 → 解法，按序实施）

| # | 压测暴露的瓶颈 | 分布式解法 | 引入组件 |
|---|---------------|-----------|---------|
| 1 | 热点读打爆 DB | 多级缓存，处理穿透/击穿/雪崩 | Redis + Spring Data Redis |
| 2 | 秒杀并发扣库存超卖 | Redis 原子扣减（Lua）+ 预扣库存 | Redisson（分布式锁） |
| 3 | 下单链路长、峰值冲击 | 异步下单、削峰、订单超时取消 | RabbitMQ 或 RocketMQ |
| 4 | 单库读写均到顶 | 读写分离 → 分库分表 | ShardingSphere |
| 5 | 服务耦合、无法独立扩容 | 按域拆微服务 + 网关 | Spring Cloud Alibaba：Nacos、Gateway |
| 6 | 恶意流量 / 服务雪崩 | 限流、熔断、降级 | Sentinel |
| 7 | 跨服务数据一致性 | 分布式事务 | Seata 或 MQ 最终一致性（推荐后者） |
| 8 | 单机部署到顶 | 集群 + 负载均衡 | Nginx upstream / 多实例部署 |
| 9 | 全链路看不清 | 链路追踪 | SkyWalking（可选，最后加） |

**微服务拆分建议**（阶段 5 时）：用户服务、商品服务、订单服务、库存服务、秒杀服务、网关

## 演进节奏

1. 单体阶段功能完整闭环，能真实下单支付（模拟）
2. 每次只解决一个瓶颈，避免一步到位
3. 每轮改造保留压测前后对比数据（QPS / RT / 错误率），这是项目最有价值的部分
