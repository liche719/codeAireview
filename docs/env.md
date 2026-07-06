# 环境变量

`.env.example` 是可提交模板，`.env` 是本地真实配置，不要提交真实密钥。

本地启动建议：

```powershell
copy .env.example .env
```

## 内部 REST API

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CODEPILOT_API_AUTH_ENABLED` | `true` | 是否启用 API Key 鉴权。 |
| `CODEPILOT_API_AUTH_API_KEY` | 空 | 内部 REST API 密钥；为空时受保护接口返回 `401`。 |
| `CODEPILOT_API_AUTH_HEADER_NAME` | `X-CodePilot-Api-Key` | API Key header 名称。 |
| `CODEPILOT_API_AUTH_PROTECTED_PATH_PATTERNS` | `/api/**` | 需要鉴权的路径。 |
| `CODEPILOT_API_AUTH_EXCLUDE_PATH_PATTERNS` | `/api/github/webhook,/api/github/webhook/**` | 鉴权排除路径；Webhook 使用 GitHub HMAC 验签。 |

## API 限流

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CODEPILOT_API_RATE_LIMIT_ENABLED` | `true` | 是否启用固定窗口限流。 |
| `CODEPILOT_API_RATE_LIMIT_MAX_REQUESTS_PER_WINDOW` | `60` | 每个 API Key/IP 的窗口请求数。 |
| `CODEPILOT_API_RATE_LIMIT_WINDOW` | `60s` | 限流窗口。 |
| `CODEPILOT_API_RATE_LIMIT_PROTECTED_PATH_PATTERNS` | `/api/**` | 限流路径。 |
| `CODEPILOT_API_RATE_LIMIT_EXCLUDE_PATH_PATTERNS` | 空 | 限流排除路径。 |

限流触发时返回 `429`，并带 `Retry-After`、`X-RateLimit-Limit`、`X-RateLimit-Remaining`、`X-RateLimit-Reset`。

## GitHub

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CODEPILOT_GITHUB_AUTH_MODE` | `auto` | `auto`、`pat` 或 `app`。 |
| `CODEPILOT_GITHUB_TOKEN` | 空 | PAT 模式 token。 |
| `CODEPILOT_GITHUB_APP_ID` | 空 | GitHub App ID。 |
| `CODEPILOT_GITHUB_APP_PRIVATE_KEY` | 空 | GitHub App PEM 私钥原文或转义换行。 |
| `CODEPILOT_GITHUB_APP_PRIVATE_KEY_BASE64` | 空 | GitHub App 私钥 base64，推荐用于 Docker/CI。 |
| `CODEPILOT_GITHUB_APP_INSTALLATION_ID` | 空 | 可选固定 installation id。为空时按仓库查询。 |
| `CODEPILOT_GITHUB_APP_TOKEN_CACHE_SKEW_SECONDS` | `60` | installation token 过期前提前刷新秒数。 |
| `CODEPILOT_GITHUB_ALLOWED_REPOSITORIES` | 空 | 允许处理的仓库列表，如 `owner/repo,org/service`。生产建议必填。 |
| `CODEPILOT_GITHUB_RATE_LIMIT_MAX_ATTEMPTS` | `3` | GitHub rate limit 或临时失败重试次数。 |
| `CODEPILOT_GITHUB_RATE_LIMIT_INITIAL_DELAY_MILLIS` | `1000` | 初始退避时间。 |
| `CODEPILOT_GITHUB_RATE_LIMIT_BACKOFF_MULTIPLIER` | `2.0` | 退避倍数。 |
| `CODEPILOT_GITHUB_RATE_LIMIT_MAX_DELAY_MILLIS` | `10000` | 最大退避时间。 |

推荐权限：

- `Metadata: Read`
- `Contents: Read`
- `Pull requests: Read and write`
- `Issues: Read and write`
- 如果启用 fix：`Contents: Read and write`

## GitHub 评论与命令

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CODEPILOT_GITHUB_COMMENT_ENABLED` | `false` | 是否回写 PR Summary Comment。 |
| `CODEPILOT_GITHUB_COMMENT_MARKER` | `<!-- codepilot-ai-review:liche719/codeAireview -->` | Summary Comment 幂等 marker。 |
| `CODEPILOT_GITHUB_INLINE_COMMENT_ENABLED` | `false` | 是否开启 inline review comment。 |
| `CODEPILOT_GITHUB_INLINE_COMMENT_MAX_PER_TASK` | `10` | 单个任务最多发布 inline comment 数。 |
| `CODEPILOT_GITHUB_BOT_MENTION_ALIASES` | `@x-pilotx,@X-PilotX` | PR 评论命令 bot mention。 |
| `CODEPILOT_GITHUB_ALLOWED_COMMENT_AUTHOR_ASSOCIATIONS` | `OWNER,MEMBER,COLLABORATOR` | 允许触发 PR 命令的 GitHub author association。 |

支持命令：

```text
/review
@x-pilotx review
@x-pilotx fix dry-run
@x-pilotx fix
```

## GitHub Webhook

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CODEPILOT_GITHUB_WEBHOOK_ENABLED` | `false` | 是否启用 Webhook 入口。 |
| `CODEPILOT_GITHUB_WEBHOOK_SECRET` | 空 | GitHub Webhook Secret，用于 HMAC 验签。 |
| `CODEPILOT_GITHUB_WEBHOOK_SKIP_SIGNATURE_WHEN_SECRET_EMPTY` | `false` | 仅限可信本地调试。生产不要开启。 |

## 自动修复与 patch validation

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CODEPILOT_GITHUB_FIX_ENABLED` | `false` | 是否启用 `@x-pilotx fix`。 |
| `CODEPILOT_GITHUB_FIX_MAX_FILES` | `3` | 单次 patch 最多修改文件数。 |
| `CODEPILOT_GITHUB_FIX_MAX_CHANGED_LINES` | `120` | 单次 patch 最多新增/删除行数。 |
| `CODEPILOT_GITHUB_FIX_VALIDATION_COMMAND` | `git diff --check` | push 前校验命令。 |
| `CODEPILOT_GITHUB_FIX_ALLOWED_VALIDATION_COMMANDS` | `git diff --check` | 校验命令白名单，精确匹配规范化 argv。 |
| `CODEPILOT_GITHUB_FIX_VALIDATION_ALLOW_BUILD_COMMANDS` | `false` | 是否允许 Maven/Gradle/npm 等构建类命令。 |
| `CODEPILOT_GITHUB_FIX_VALIDATION_EXECUTION_MODE` | `local` | `local` 或 `docker`。构建类命令必须使用 `docker`。 |
| `CODEPILOT_GITHUB_FIX_VALIDATION_DOCKER_IMAGE` | 空 | sandbox 镜像，如 `maven:3.9-eclipse-temurin-21`。 |
| `CODEPILOT_GITHUB_FIX_VALIDATION_DOCKER_NETWORK` | `none` | sandbox 网络模式。生产建议保持 `none`。 |
| `CODEPILOT_GITHUB_FIX_VALIDATION_INHERIT_ENVIRONMENT` | `false` | local 校验进程是否继承服务环境变量。 |
| `CODEPILOT_GITHUB_FIX_VALIDATION_TIMEOUT_SECONDS` | `300` | 校验超时。 |

## LLM

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CODEPILOT_LLM_API_KEY` | 空 | Chat model API Key。 |
| `CODEPILOT_LLM_BASE_URL` | `https://api.openai.com/v1` | OpenAI-compatible base URL。 |
| `CODEPILOT_LLM_MODEL` | `gpt-4o-mini` | Chat model 名称。 |
| `CODEPILOT_LLM_REVIEW_STRUCTURED_OUTPUT_ENABLED` | `true` | 是否启用 provider-native JSON schema。 |
| `CODEPILOT_LLM_MAX_REVIEW_PATCH_CHARS` | `12000` | 单文件传给 LLM 的 patch 上限。 |
| `CODEPILOT_LLM_MAX_REVIEW_RULES_CHARS` | `4000` | 规则上下文上限。 |
| `CODEPILOT_LLM_MAX_REVIEW_CONTEXT_CHARS` | `8000` | 额外上下文上限。 |
| `CODEPILOT_LLM_REVIEW_CACHE_ENABLED` | `true` | 是否启用 LLM 审查缓存。 |
| `CODEPILOT_LLM_REVIEW_CACHE_TTL_DAYS` | `7` | LLM 缓存 TTL。 |
| `CODEPILOT_LLM_REVIEW_CACHE_CLEANUP_ENABLED` | `true` | 是否启用 LLM 缓存清理任务。 |
| `CODEPILOT_LLM_REVIEW_CACHE_CLEANUP_CRON` | `0 0 */6 * * *` | 缓存清理 cron。 |
| `CODEPILOT_LLM_CALL_LOG_CLEANUP_ENABLED` | `true` | 是否清理 LLM 调用日志。 |
| `CODEPILOT_LLM_CALL_LOG_RETENTION_DAYS` | `30` | 调用日志保留天数。 |
| `CODEPILOT_LLM_CALL_LOG_CLEANUP_CRON` | `0 30 */6 * * *` | 调用日志清理 cron。 |

## Embedding

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CODEPILOT_EMBEDDING_API_KEY` | 继承 LLM Key | Embedding API Key。 |
| `CODEPILOT_EMBEDDING_BASE_URL` | 继承 LLM base URL | Embedding base URL。 |
| `CODEPILOT_EMBEDDING_MODEL` | `text-embedding-3-small` | Embedding model。 |
| `CODEPILOT_EMBEDDING_DIMENSION` | `0` | 向量维度。`0` 表示由模型解析。 |

## RAG

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CODEPILOT_RAG_CACHE_ENABLED` | `true` | 是否启用 RAG 召回缓存。 |
| `CODEPILOT_RAG_CACHE_MAX_SIZE` | `256` | RAG LRU 缓存最大条目数。 |
| `CODEPILOT_RAG_CACHE_TTL_SECONDS` | `300` | RAG 缓存 TTL。 |

## 审查限制与文件级并发

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CODEPILOT_REVIEW_MAX_FILES_PER_TASK` | `30` | 单个任务最多进入 AI Review 的文件数。 |
| `CODEPILOT_REVIEW_MAX_SUMMARY_FINDINGS` | `20` | Summary Comment 最多展示问题数。 |
| `CODEPILOT_REVIEW_MAX_PATCH_CHARS_PER_FILE` | `12000` | 单文件 patch 字符上限。 |
| `CODEPILOT_REVIEW_MAX_TOTAL_PATCH_CHARS` | `80000` | 单任务 patch 总字符上限。 |
| `CODEPILOT_REVIEW_MAX_PARALLEL_FILES` | `2` | 单个任务内文件级并发数。 |
| `CODEPILOT_REVIEW_MAX_REPO_CONTEXT_FILES` | `6` | 最多召回相关仓库上下文文件数。 |
| `CODEPILOT_REVIEW_MAX_REPO_CONTEXT_FILE_CHARS` | `20000` | 单个相关源文件最大读取字符数。 |
| `CODEPILOT_REVIEW_MAX_REPO_CONTEXT_EXCERPT_CHARS` | `900` | 注入 prompt 的单片段上限。 |

## 数据库和中间件

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CODEPILOT_DB_NAME` | `codepilot` | Docker Compose 初始化数据库名。 |
| `CODEPILOT_DB_URL` | `jdbc:postgresql://localhost:15432/codepilot` | JDBC URL。 |
| `CODEPILOT_DB_USERNAME` | `codepilot` | PostgreSQL 用户。 |
| `CODEPILOT_DB_PASSWORD` | `codepilot123` | PostgreSQL 密码，仅适合本地。 |
| `CODEPILOT_REDIS_HOST` | `localhost` | Redis host。 |
| `CODEPILOT_REDIS_PORT` | `16379` | Redis port。 |
| `CODEPILOT_REDIS_DATABASE` | `0` | Redis database。 |
| `CODEPILOT_REDIS_TIMEOUT` | `3s` | Redis 超时。 |
| `CODEPILOT_RABBITMQ_HOST` | `localhost` | RabbitMQ host。 |
| `CODEPILOT_RABBITMQ_PORT` | `5672` | RabbitMQ AMQP port。 |
| `CODEPILOT_RABBITMQ_USERNAME` | `codepilot` | RabbitMQ 用户。 |
| `CODEPILOT_RABBITMQ_PASSWORD` | `codepilot123` | RabbitMQ 密码。 |
| `CODEPILOT_RABBITMQ_VIRTUAL_HOST` | `/` | RabbitMQ virtual host。 |
| `CODEPILOT_RABBITMQ_DEFAULT_REQUEUE_REJECTED` | `false` | retry 耗尽后是否重新入队。生产建议保持 `false`。 |
| `CODEPILOT_RABBITMQ_LISTENER_CONCURRENCY` | `2` | Rabbit listener 初始并发。 |
| `CODEPILOT_RABBITMQ_LISTENER_MAX_CONCURRENCY` | `4` | Rabbit listener 最大并发。 |
| `CODEPILOT_RABBITMQ_LISTENER_PREFETCH` | `1` | 每个 consumer 预取消息数。 |

## 生产建议

- 保持 `CODEPILOT_API_AUTH_ENABLED=true`。
- 配置强随机 `CODEPILOT_API_AUTH_API_KEY`。
- Webhook 开启时必须配置 `CODEPILOT_GITHUB_WEBHOOK_SECRET`。
- 配置 `CODEPILOT_GITHUB_ALLOWED_REPOSITORIES`，避免任意仓库消耗模型/GitHub 配额。
- 优先使用 GitHub App，而不是长期 PAT。
- 不要在公开环境关闭 API 鉴权或 Webhook 验签。
- 不要把 `.env`、token、私钥、真实模型响应或私有仓库 diff 提交到 Git。
