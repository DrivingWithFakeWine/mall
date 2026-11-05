# DoroShop 部署说明（ECS + RDS + Docker Compose）

## 架构

```
浏览器
  │  :80 (后续加 HTTPS)
  ▼
Nginx 容器 ──静态文件──► 前端 dist（React 构建产物）
  │ /api/* 反代（去掉 /api 前缀）
  ▼
Backend 容器 :8080（Spring Boot，prod profile）
  │ 内网 3306            │ 容器网络 6379
  ▼                      ▼
RDS MySQL（doro_mall）   Redis 容器（秒杀闸门，不持久化）
```

> `.env` 需要新增 `REDIS_PASSWORD`；Redis 不发布端口，仅 backend 通过容器网络访问。

## 服务器目录

```
/opt/doroshop/
├── docker-compose.yml
├── .env                 # 数据库密码、JWT 密钥（不入 git）
├── backend/Shop.jar
├── frontend/dist/
├── nginx/conf.d/default.conf
└── certs/               # HTTPS 证书（暂未使用）
```

## 首次部署

### 1. 本地构建

```bash
cd Shop && mvn clean package -DskipTests
cd shop-web && npm run build
```

产物：
- `Shop/target/Shop-0.0.1-SNAPSHOT.jar`
- `shop-web/dist/`

### 2. 上传

```bash
scp Shop/target/Shop-0.0.1-SNAPSHOT.jar root@ECS_IP:/opt/doroshop/backend/Shop.jar
scp -r shop-web/dist/* root@ECS_IP:/opt/doroshop/frontend/dist/
scp -r deploy/* root@ECS_IP:/opt/doroshop/
```

### 3. 配置 .env

在服务器上：

```bash
cd /opt/doroshop
cp .env.example .env
vi .env
```

填写 `DB_PASSWORD` 与 `JWT_SECRET`（至少 32 字节，可用 `openssl rand -base64 32` 生成）。

### 4. 启动

```bash
cd /opt/doroshop
docker compose pull
docker compose up -d
docker compose ps
docker compose logs -f backend
```

### 5. 验证

```bash
curl -I http://localhost/health
curl http://localhost/api/product/list?page=1&size=3
```

浏览器访问 `http://ECS公网IP/`。

## 日常操作

| 操作 | 命令 |
|------|------|
| 查看状态 | `docker compose ps` |
| 查看日志 | `docker compose logs -f backend` |
| 重启后端 | `docker compose restart backend` |
| 更新后端 | 上传新 jar → `docker compose restart backend` |
| 更新前端 | 上传新 dist → 无需重启（Nginx 直接读文件） |
| 停止 | `docker compose down` |

## 后续：HTTPS

域名备案完成后：

1. 解析 A 记录到 ECS 公网 IP
2. 申请证书（阿里云免费证书或 certbot），放到 `certs/`
3. 在 `nginx/conf.d/default.conf` 增加 443 server 块，并让 80 跳转 HTTPS
4. 挂载 `./certs:/etc/nginx/certs:ro`，`docker compose restart nginx`

## 说明

- 后端 JVM 参数在 `docker-compose.yml` 的 `command` 里，2C4G 机器给到 `-Xmx1g`
- 数据库无容器：直接连 RDS 内网地址（`DB_URL`）
- 前端所有请求使用 `/api` 前缀，Nginx 负责剥离；本地开发时由 Vite 代理完成同样的事
