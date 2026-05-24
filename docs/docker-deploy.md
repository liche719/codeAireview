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

生产环境建议同时设置 `CODEPILOT_GITHUB_ALLOWED_REPOSITORIES=owner/repo`，只允许受信仓库触发手动审查、Webhook 自动审查和 PR 评论命令，避免同一个 GitHub Token 被任意仓库消耗或越过数据边界。

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

## 5. GitHub Actions 部署配置

如果使用 `.github/workflows/deploy.yml` 自动部署，需要在 GitHub 仓库中配置这些值：

- Repository variables：`CODEPILOT_DEPLOY_HOST`、`CODEPILOT_DEPLOY_USER`、`CODEPILOT_DEPLOY_DIR`。
- Repository secrets：`CODEPILOT_DEPLOY_SSH_KEY`、`CODEPILOT_DEPLOY_KNOWN_HOSTS`。

`CODEPILOT_DEPLOY_SSH_KEY` 应该是专用部署用户的 SSH 私钥，不要使用 root 密码。`CODEPILOT_DEPLOY_KNOWN_HOSTS` 必须填写已固定的服务器 host key，例如通过 `ssh-keyscan your-host` 获取后人工核验再保存，避免 CI 在部署时跳过主机校验。

## 6. 常见调整

- 如果 8080 被占用，可以修改 `docker-compose.server.yml` 里的端口映射。
- 如果你要放到 Nginx / Caddy 后面，可以只对外暴露反向代理端口。
- RabbitMQ 管理界面默认已经映射到 `15672`，直接访问 `http://<host>:15672` 即可。
- 当前统一端口为：PostgreSQL 容器内 `5432` / 宿主机 `15432`，Redis 容器内 `6379` / 宿主机 `16379`，RabbitMQ AMQP `5672`，RabbitMQ 管理界面 `15672`，应用 `8080`。
- `@x-pilotx fix` 默认关闭，开启 `CODEPILOT_GITHUB_FIX_ENABLED=true` 后才会在临时检出的 PR 分支里执行校验命令。默认校验命令是 `git diff --check`，不会执行 PR 内构建脚本；如果改成 Maven/Gradle/npm 等构建命令，必须同时配置 `CODEPILOT_GITHUB_FIX_ALLOWED_VALIDATION_COMMANDS`。校验命令不会通过 shell 执行，并且不允许 `./gradlew`、绝对路径、管道或重定向；构建类命令仍需要先准备隔离沙箱。校验超时时间可通过 `CODEPILOT_GITHUB_FIX_VALIDATION_TIMEOUT_SECONDS` 调整。

生产环境可以通过 `CODEPILOT_API_RATE_LIMIT_MAX_REQUESTS_PER_WINDOW` 和 `CODEPILOT_API_RATE_LIMIT_WINDOW` 调整应用层固定窗口限流。它是单实例成本保护，不替代反向代理层的 IP allowlist / rate limit；如果部署多副本，仍需要在网关层做集中式限流。

## 7. 说明

- `.env` 不要提交。
- `.env.example` 是模板，可以提交。
- `docker-compose.server.yml` 适合服务器部署，`docker-compose.yml` 继续保留给本地基础设施使用。
- 如果应用暴露到公网，保持 `CODEPILOT_API_AUTH_ENABLED=true`，并在反向代理层继续补充 IP allowlist / rate limit。
