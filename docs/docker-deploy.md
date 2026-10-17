# Docker 部署

这份文档用于把 CodePilot AI 部署到 Linux 服务器。生产暴露前请先读 [env.md](env.md) 和 [github-auth.md](github-auth.md)。

## 前置条件

- Docker Engine 24+
- Docker Compose v2
- 一份真实 `.env`
- 可用的 GitHub PAT 或 GitHub App 配置
- 可用的 LLM / Embedding API Key

## 1. 准备配置

```bash
cp .env.example .env
```

至少需要设置：

```env
CODEPILOT_API_AUTH_API_KEY=<strong random key>
CODEPILOT_GITHUB_AUTH_MODE=app
CODEPILOT_GITHUB_APP_ID=<app id>
CODEPILOT_GITHUB_APP_PRIVATE_KEY_BASE64=<base64 private key>
CODEPILOT_GITHUB_ALLOWED_REPOSITORIES=owner/repo
CODEPILOT_GITHUB_WEBHOOK_ENABLED=true
CODEPILOT_GITHUB_WEBHOOK_SECRET=<strong random webhook secret>
CODEPILOT_GITHUB_COMMENT_ENABLED=true
CODEPILOT_LLM_API_KEY=<llm key>
CODEPILOT_EMBEDDING_API_KEY=<embedding key>
```

本地或小型自托管也可以使用 PAT：

```env
CODEPILOT_GITHUB_AUTH_MODE=pat
CODEPILOT_GITHUB_TOKEN=<fine-grained token>
```

生产建议配置 `CODEPILOT_GITHUB_ALLOWED_REPOSITORIES`，避免任意仓库触发任务并消耗 GitHub/LLM 配额。

## 2. 启动

```bash
docker compose -f docker-compose.server.yml up -d --build
```

该 compose 会启动：

- PostgreSQL + pgvector
- Redis
- RabbitMQ
- CodePilot AI 应用容器

依赖服务通过 Docker 网络互相访问，数据库和中间件不需要直接暴露到公网。

## 3. 查看状态

```bash
docker compose -f docker-compose.server.yml ps
docker compose -f docker-compose.server.yml logs -f app
```

RabbitMQ 管理界面默认映射到 `15672`。如果部署在公网，请通过防火墙、VPN 或反向代理鉴权保护它。

## 4. 配置 GitHub Webhook

在 GitHub 仓库 `Settings -> Webhooks` 中新增：

```text
Payload URL: https://your-domain/api/github/webhook
Content type: application/json
Secret: 与 CODEPILOT_GITHUB_WEBHOOK_SECRET 一致
Events: Pull requests, Issue comments
```

支持：

- PR 打开、同步、重新打开时自动审查。
- PR Conversation 评论 `/review` 或 `@x-pilotx review` 手动触发。
- 所有 `@x-pilotx ...` 评论进入 Agent；显式开启 fix 模式后，Agent 才能按具体请求启动 dry-run 修复预览。发布和合并还需要各自的服务端开关与独立用户审批。

## 5. 反向代理建议

建议用 Nginx / Caddy / Traefik 做 TLS 和公网入口。

最少要保护：

- 只暴露应用对外端口（默认宿主机 **`8990`** → 容器 `8080`），不要暴露 PostgreSQL、Redis、RabbitMQ AMQP 到公网。
  刻意不用 `8080`：公网上它是无差别扫描最常探的端口之一。**换端口本身不算防护**——真正的门是 API 鉴权 + webhook HMAC + 安全组，换端口只是少挨扫描。
- 保持 `CODEPILOT_API_AUTH_ENABLED=true`。
- Webhook 必须配置 secret。
- 在代理层补充 IP allowlist / rate limit。
- 生产设置强随机 API Key，不使用 `.env.example` 的示例值。

## 6. 自动修复模式的部署注意

Agent 修复预览、发布和合并能力默认分别关闭：

```env
CODEPILOT_GITHUB_FIX_ENABLED=false
CODEPILOT_GITHUB_FIX_PUBLISH_ENABLED=false
CODEPILOT_GITHUB_FIX_MERGE_ENABLED=false
CODEPILOT_GITHUB_FIX_APPROVAL_REQUIRE_SAME_USER=true
```

开启 preview 后，系统会从当前 PR head SHA 对应的成功审查结果中选择可修复 issue，生成小 patch 并执行 dry-run 校验。它不会直接改原 PR 分支。

只有用户精确评论 `@x-pilotx 批准发布修复 <previewTaskId>` 且服务端审批校验通过后，系统才将同一 patch 推送到同仓库独立 `codepilot/fix-*` 分支，并创建以原 PR head 分支为 base 的修复 PR。合并还要求第二条 `@x-pilotx 批准合并修复 <publishTaskId>` 评论。部署使用的 GitHub PAT/App 必须具有 `Contents: Read and write` 和 `Pull requests: Read and write`；生产必须同时配置 repository allowlist。

默认校验只允许：

```env
CODEPILOT_GITHUB_FIX_VALIDATION_COMMAND=git diff --check
CODEPILOT_GITHUB_FIX_ALLOWED_VALIDATION_COMMANDS=git diff --check
CODEPILOT_GITHUB_FIX_VALIDATION_EXECUTION_MODE=local
```

如果想运行 Maven/Gradle/npm 等会执行 PR 代码的命令，必须显式使用 Docker sandbox：

```env
CODEPILOT_GITHUB_FIX_VALIDATION_ALLOW_BUILD_COMMANDS=true
CODEPILOT_GITHUB_FIX_VALIDATION_EXECUTION_MODE=docker
CODEPILOT_GITHUB_FIX_VALIDATION_DOCKER_IMAGE=maven:3.9-eclipse-temurin-21
CODEPILOT_GITHUB_FIX_VALIDATION_DOCKER_NETWORK=none
```

`docker-compose.server.yml` 默认不挂载 `/var/run/docker.sock`。这是刻意的安全默认值，因为 Docker socket 近似宿主机级权限。只有在专用隔离主机上确实需要构建校验时，才考虑显式挂载 Docker socket 或接入远程 sandbox runner。

## 7. 扩容和并发

关键配置：

```env
CODEPILOT_RABBITMQ_LISTENER_CONCURRENCY=2
CODEPILOT_RABBITMQ_LISTENER_MAX_CONCURRENCY=4
CODEPILOT_RABBITMQ_LISTENER_PREFETCH=1
CODEPILOT_REVIEW_MAX_PARALLEL_FILES=2
CODEPILOT_REVIEW_MAX_FILES_PER_TASK=30
```

调大并发前先评估：

- GitHub API rate limit。
- LLM provider QPS 和费用。
- PostgreSQL 连接数。
- RabbitMQ 消费积压和 DLQ。
- 单个 PR 内大文件数量。

## 8. 停止和升级

停止：

```bash
docker compose -f docker-compose.server.yml down
```

保留数据卷升级：

```bash
git pull
docker compose -f docker-compose.server.yml up -d --build
```

Flyway 会在应用启动时执行数据库迁移。

## 9. 常见端口

- 应用：宿主机 `8990` → 容器 `8080`
- PostgreSQL 宿主机映射：`15432`
- Redis 宿主机映射：`16379`
- RabbitMQ AMQP：`5672`
- RabbitMQ Management：`15672`

生产环境通常只需要公网暴露应用反向代理入口。
