# 环境变量

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

## LLM

- `CODEPILOT_LLM_API_KEY`
  - LLM API Key。
- `CODEPILOT_LLM_BASE_URL`
  - OpenAI-compatible Chat Completions 接口地址。
- `CODEPILOT_LLM_MODEL`
  - Chat Model 名称。

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

## GitHub 命令代理

- `CODEPILOT_GITHUB_BOT_MENTION_ALIASES`
  - PR 评论中的机器人 mention 别名，默认 `@x-pilotx,@X-PilotX`。
- `CODEPILOT_GITHUB_FIX_ENABLED`
  - 是否启用 `@x-pilotx fix` 和 `@x-pilotx fix dry-run`，默认 `true`。
- `CODEPILOT_GITHUB_FIX_MAX_FILES`
  - 单个补丁最多可修改的文件数，默认 `3`。
- `CODEPILOT_GITHUB_FIX_MAX_CHANGED_LINES`
  - 单个补丁最多可包含的新增/删除行数，默认 `120`。
- `CODEPILOT_GITHUB_FIX_VALIDATION_COMMAND`
  - 提交和推送前执行的校验命令，默认 `mvn -q -DskipTests compile`。

命令示例：
- `/review`
- `@x-pilotx review`
- `@x-pilotx fix dry-run`
- `@x-pilotx fix`

修复模式只会推送到同仓库的当前 PR head 分支。Fork PR 不支持自动修复。Fine-grained GitHub Token 在修复模式下需要 `Contents: Read and write`、`Pull requests: Read and write`、`Issues: Read and write` 和 `Metadata: Read`。
