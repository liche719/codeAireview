# 环境变量

## 内部 REST API 认证

- `CODEPILOT_API_AUTH_ENABLED`
  - 是否启用内部 REST API Key 鉴权，默认 `true`。
- `CODEPILOT_API_AUTH_API_KEY`
  - 内部 REST API 访问密钥。默认保护 `/api/**`，未配置时受保护接口会返回 `401`，避免公网裸奔。生产环境必须替换为强随机值。
- `CODEPILOT_API_AUTH_HEADER_NAME`
  - API Key 请求头名称，默认 `X-CodePilot-Api-Key`。
- `CODEPILOT_API_AUTH_PROTECTED_PATH_PATTERNS`
  - 需要鉴权的路径模式，默认 `/api/**`。
- `CODEPILOT_API_AUTH_EXCLUDE_PATH_PATTERNS`
  - 鉴权排除路径，默认 `/api/github/webhook,/api/github/webhook/**`。GitHub Webhook 入口依赖 GitHub HMAC 签名，不使用内部 API Key。

## 内部 REST API 限流

- `CODEPILOT_API_RATE_LIMIT_ENABLED`
  - 是否启用内部 REST API 固定窗口限流，默认 `true`。
- `CODEPILOT_API_RATE_LIMIT_MAX_REQUESTS_PER_WINDOW`
  - 每个 API Key / IP 在一个窗口内允许的最大请求数，默认 `60`。
- `CODEPILOT_API_RATE_LIMIT_WINDOW`
  - 限流窗口大小，默认 `60s`。
- `CODEPILOT_API_RATE_LIMIT_PROTECTED_PATH_PATTERNS`
  - 需要限流的路径模式，默认 `/api/**`。
- `CODEPILOT_API_RATE_LIMIT_EXCLUDE_PATH_PATTERNS`
  - 限流排除路径，默认空。生产环境如果要排除 GitHub Webhook，必须确认 Webhook Secret 和上游限流已经正确配置。

触发限流时接口返回 `429`，并带 `Retry-After`、`X-RateLimit-Limit`、`X-RateLimit-Remaining`、`X-RateLimit-Reset` 响应头。该限流是单实例内存保护，适合防止 API Key 泄露或误用导致 LLM 成本暴涨；多实例生产环境仍建议在 Nginx / Caddy / API Gateway 层补充集中式限流。

## GitHub

- `CODEPILOT_GITHUB_TOKEN`
  - GitHub Token，用于拉取 PR 文件、查询/创建/更新 PR 评论。
- `CODEPILOT_GITHUB_COMMENT_ENABLED`
  - 是否开启 GitHub PR 评论回写，默认 `false`。
- `CODEPILOT_GITHUB_COMMENT_MARKER`
  - CodePilot PR 评论识别标记，默认 `<!-- codepilot-ai-review:liche719/codeAireview -->`。
- `CODEPILOT_GITHUB_INLINE_COMMENT_ENABLED`
  - 是否开启 GitHub PR inline review comment，默认 `false`。关闭时仍可使用顶部 Summary Comment。
- `CODEPILOT_GITHUB_INLINE_COMMENT_MAX_PER_TASK`
  - 单个 ReviewTask 最多创建多少条 inline comment，默认 `10`。
- `CODEPILOT_GITHUB_WEBHOOK_ENABLED`
  - 是否开启 GitHub Webhook 入口，默认 `false`。
- `CODEPILOT_GITHUB_WEBHOOK_SECRET`
  - GitHub Webhook Secret，用于 `X-Hub-Signature-256` 验签。
- `CODEPILOT_GITHUB_WEBHOOK_SKIP_SIGNATURE_WHEN_SECRET_EMPTY`
  - 本地调试用开关。默认 `false`，仅在确认接口不会暴露给不可信来源时才可设为 `true`。
- `CODEPILOT_GITHUB_ALLOWED_REPOSITORIES`
  - 允许处理的 GitHub 仓库列表，格式如 `owner/repo,org/service`。默认空表示不限制；生产环境建议显式配置，未在 allowlist 内的仓库会被拒绝创建审查任务，也不能通过 PR 评论命令触发 `review/fix/chat`。

## LLM

- `CODEPILOT_LLM_API_KEY`
  - LLM API Key。
- `CODEPILOT_LLM_BASE_URL`
  - OpenAI-compatible Chat Completions 接口地址。
- `CODEPILOT_LLM_MODEL`
  - Chat Model 名称。
- `CODEPILOT_LLM_REVIEW_STRUCTURED_OUTPUT_ENABLED`
  - 是否为 AI Review 专用模型启用 provider-native JSON schema 结构化输出，默认 `true`。如果使用的 OpenAI-compatible 服务不支持 JSON schema response format，可临时设置为 `false`，系统仍会保留后验 JSON parser/schema 校验。

## 数据库 / 中间件

- `CODEPILOT_DB_URL`
  - PostgreSQL JDBC 地址，默认 `jdbc:postgresql://localhost:15432/codepilot`。
- `CODEPILOT_DB_NAME`
  - Docker Compose 初始化 PostgreSQL 数据库名，默认 `codepilot`。
- `CODEPILOT_DB_USERNAME`
  - PostgreSQL 用户名，默认 `codepilot`。
- `CODEPILOT_DB_PASSWORD`
  - PostgreSQL 密码，默认 `codepilot123`，仅建议本地开发使用。
- `CODEPILOT_REDIS_HOST`
  - Redis 主机，默认 `localhost`。
- `CODEPILOT_REDIS_PORT`
  - Redis 端口，默认 `16379`。
- `CODEPILOT_REDIS_DATABASE`
  - Redis database，默认 `0`。
- `CODEPILOT_REDIS_TIMEOUT`
  - Redis 连接超时，默认 `3s`。
- `CODEPILOT_RABBITMQ_HOST`
  - RabbitMQ 主机，默认 `localhost`。
- `CODEPILOT_RABBITMQ_PORT`
  - RabbitMQ AMQP 端口，默认 `5672`。
- `CODEPILOT_RABBITMQ_USERNAME`
  - RabbitMQ 用户名，默认 `codepilot`。
- `CODEPILOT_RABBITMQ_PASSWORD`
  - RabbitMQ 密码，默认 `codepilot123`，仅建议本地开发使用。
- `CODEPILOT_RABBITMQ_VIRTUAL_HOST`
  - RabbitMQ virtual host，默认 `/`。
- `CODEPILOT_RABBITMQ_DEFAULT_REQUEUE_REJECTED`
  - Listener 重试耗尽后是否重新入队，默认 `false`。审查任务队列和 PR 命令队列已配置 DLQ：`codepilot.review.task.dlq`、`codepilot.pr.command.task.dlq`。生产环境仍不建议设为 `true`，否则永久失败消息可能反复回到主队列，绕过死信排查流程并造成队列积压。

## Embedding

- `CODEPILOT_EMBEDDING_API_KEY`
  - Embedding API Key。
- `CODEPILOT_EMBEDDING_BASE_URL`
  - Embedding 接口地址。
- `CODEPILOT_EMBEDDING_MODEL`
  - Embedding 模型名。

## 审查限制

- `CODEPILOT_REVIEW_MAX_FILES_PER_TASK`
  - 单个任务最多进入 AI Review 的文件数，默认 `30`。
- `CODEPILOT_REVIEW_MAX_PATCH_CHARS_PER_FILE`
  - 单个文件 patch 最大字符数，默认 `12000`。
- `CODEPILOT_REVIEW_MAX_TOTAL_PATCH_CHARS`
  - 单个任务累计进入 AI Review 的 patch 最大字符数，默认 `80000`。

## 说明

- 没有配置 `CODEPILOT_GITHUB_TOKEN` 时，GitHub 评论回写会跳过。
- Fine-grained GitHub Token 建议权限：`Contents: Read`、`Pull requests: Read and write`、`Issues: Read and write`、`Metadata: Read`。
- 只使用顶部 Summary Comment 时主要依赖 Issues 评论权限；开启 inline comment 后需要 `Pull requests: Read and write`。
- Webhook 默认关闭，开启时建议同时配置 `CODEPILOT_GITHUB_WEBHOOK_SECRET`。
- 没有配置 `CODEPILOT_GITHUB_WEBHOOK_SECRET` 且未显式开启跳过验签时，Webhook 验签会失败。
- 没有配置 LLM 或 Embedding Key 时，应用仍可启动，相关能力会降级为跳过。
- `codepilot123` 是本地开发默认密码，生产或公开环境必须通过环境变量覆盖。

补充说明：
- `.env` 不要提交，`.env.example` 是可提交的本地模板。
- 本地推荐使用 `scripts/start-local.ps1` 启动，它会先加载 `.env`，再启动 Docker、打包和运行应用。
- 如果只做可信本地调试，可以临时设置 `CODEPILOT_API_AUTH_ENABLED=false`；不要在公网或共享环境关闭。

## GitHub 命令代理

- `CODEPILOT_GITHUB_BOT_MENTION_ALIASES`
  - PR 评论中的机器人 mention 别名，默认 `@x-pilotx,@X-PilotX`。
- `CODEPILOT_GITHUB_FIX_ENABLED`
  - 是否启用 `@x-pilotx fix` 和 `@x-pilotx fix dry-run`，默认 `false`。
- `CODEPILOT_GITHUB_ALLOWED_COMMENT_AUTHOR_ASSOCIATIONS`
  - 允许触发 PR 评论命令的 GitHub author association，默认 `OWNER,MEMBER,COLLABORATOR`。
- `CODEPILOT_GITHUB_ALLOWED_REPOSITORIES`
  - 同时限制手动审查、Webhook 自动审查和 PR 评论命令可处理的仓库。这里再次列出是因为它也会保护 `@x-pilotx review/fix/chat` 命令入口。
- `CODEPILOT_GITHUB_FIX_MAX_FILES`
  - 单个补丁最多可修改的文件数，默认 `3`。
- `CODEPILOT_GITHUB_FIX_MAX_CHANGED_LINES`
  - 单个补丁最多可包含的新增/删除行数，默认 `120`。
- `CODEPILOT_GITHUB_FIX_VALIDATION_COMMAND`
  - 提交和推送前执行的校验命令，默认 `git diff --check`。默认值只做 diff 空白检查，不执行 PR 内构建脚本。命令会被解析为固定 argv，不通过 shell 执行。
- `CODEPILOT_GITHUB_FIX_ALLOWED_VALIDATION_COMMANDS`
  - 允许执行的校验命令白名单，默认只有 `git diff --check`。白名单精确匹配规范化后的 argv，不允许 shell、仓库内 wrapper（如 `./gradlew`）、任意路径可执行文件、管道、重定向或控制字符。如果要使用 `mvn -q -DskipTests compile` 等命令，必须显式加入白名单，并意识到这会执行 PR 代码/构建插件。
- `CODEPILOT_GITHUB_FIX_VALIDATION_INHERIT_ENVIRONMENT`
  - 校验进程是否继承服务进程环境变量，默认 `false`，避免 LLM/GitHub Token 等敏感变量暴露给 PR 代码。
- `CODEPILOT_GITHUB_FIX_VALIDATION_TIMEOUT_SECONDS`
  - 修复补丁校验命令的最大等待时间，默认 `300` 秒。服务器首次运行 Maven 时可能需要下载依赖，超时过短会导致补丁已生成但不会提交。

命令示例：
- `/review`
- `@x-pilotx review`
- `@x-pilotx fix dry-run`
- `@x-pilotx fix`

修复模式只会推送到同仓库的当前 PR head 分支，且只会复用与当前 head sha 匹配的成功审查结果。Fork PR 不支持自动修复。Fine-grained GitHub Token 在修复模式下需要 `Contents: Read and write`、`Pull requests: Read and write`、`Issues: Read and write` 和 `Metadata: Read`。
