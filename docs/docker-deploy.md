# Docker 部署

这套部署方式适合把 CodePilot AI 直接跑在 Linux 服务器上。

## 前置条件

- Docker Engine 24+
- Docker Compose v2
- 一份可用的 `.env` 文件

## 1. 准备环境

先把模板复制成真实配置：

```powershell
copy .env.example .env
```

然后填写至少这些值：

- `CODEPILOT_API_AUTH_API_KEY`
- `CODEPILOT_GITHUB_TOKEN`
- `CODEPILOT_LLM_API_KEY`
- `CODEPILOT_EMBEDDING_API_KEY`
- `CODEPILOT_GITHUB_WEBHOOK_SECRET`

`CODEPILOT_API_AUTH_API_KEY` 用于保护除 GitHub Webhook 外的内部 REST API，服务器部署时必须替换为强随机值。不要沿用 `.env.example` 里的本地开发示例值。

## 2. 启动

在项目根目录执行：

```bash
docker compose -f docker-compose.server.yml up -d --build
```

这个命令会启动：

- PostgreSQL + pgvector
- Redis
- RabbitMQ
- CodePilot AI 应用容器

其中 PostgreSQL 和 RabbitMQ 都带了持久化卷，方便服务器重启后保留数据和队列状态。

应用容器会通过服务名访问依赖：

- `postgres`
- `redis`
- `rabbitmq`

所以不用把数据库和中间件额外暴露到公网。

## 3. 查看状态

```bash
docker compose -f docker-compose.server.yml ps
docker compose -f docker-compose.server.yml logs -f app
```

## 4. 停止

```bash
docker compose -f docker-compose.server.yml down
```

## 5. 常见调整

- 如果 8080 被占用，可以修改 `docker-compose.server.yml` 里的端口映射。
- 如果你要放到 Nginx / Caddy 后面，可以只对外暴露反向代理端口。
- RabbitMQ 管理界面默认已经映射到 `15672`，直接访问 `http://<host>:15672` 即可。
- 当前统一端口为：PostgreSQL 容器内 `5432` / 宿主机 `15432`，Redis 容器内 `6379` / 宿主机 `16379`，RabbitMQ AMQP `5672`，RabbitMQ 管理界面 `15672`，应用 `8080`。
- `@x-pilotx fix` 默认关闭，开启 `CODEPILOT_GITHUB_FIX_ENABLED=true` 后才会在临时检出的 PR 分支里执行校验命令。默认校验命令是 `git diff --check`，不会执行 PR 内构建脚本；如果改成 Maven/Gradle/npm 等构建命令，必须同时配置 `CODEPILOT_GITHUB_FIX_ALLOWED_VALIDATION_COMMANDS`，并先准备隔离沙箱。校验超时时间可通过 `CODEPILOT_GITHUB_FIX_VALIDATION_TIMEOUT_SECONDS` 调整。

## 6. 说明

- `.env` 不要提交。
- `.env.example` 是模板，可以提交。
- `docker-compose.server.yml` 适合服务器部署，`docker-compose.yml` 继续保留给本地基础设施使用。
- 如果应用暴露到公网，保持 `CODEPILOT_API_AUTH_ENABLED=true`，并在反向代理层继续补充 IP allowlist / rate limit。
