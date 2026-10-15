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

限流状态存储在 Redis，多实例共享同一窗口；Redis 暂时不可用时会降级到单实例内存限流，避免接口完全失去保护。

## 后台任务

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CODEPILOT_SCHEDULING_ENABLED` | `true` | 是否启动本地消息发布、执行租约恢复、GitHub 发布重试和缓存清理等后台任务。 |

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
| `CODEPILOT_GITHUB_CONNECT_TIMEOUT` | `5s` | GitHub HTTP 建连超时。 |
| `CODEPILOT_GITHUB_READ_TIMEOUT` | `30s` | GitHub HTTP 响应读取超时。 |
| `CODEPILOT_GITHUB_CALL_TIMEOUT` | `45s` | 包含限流重试在内的单次 GitHub 业务调用总时限。 |

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
| `CODEPILOT_GITHUB_COMMENT_IDENTITY_HEADER_LINES` | `5` | 评论身份标记只在正文前 N 行内匹配，避免正文引用到标记时被误判。 |

**关于评论身份标记**：系统在同一个 PR 上发布四类评论，各自使用**独立**的身份标记——审查摘要（可更新）、Agent 回复、命令提示、修复结果（后三者只追加）。
除审查摘要标记可通过 `CODEPILOT_GITHUB_COMMENT_MARKER` 覆盖外，其余标记是代码常量（`CommentMarkers`）**刻意不可配置**：允许配置只会引入"两个标记被配成同一个值"从而互相覆盖的风险。
| `CODEPILOT_GITHUB_INLINE_COMMENT_ENABLED` | `false` | 是否开启 inline review comment。 |
| `CODEPILOT_GITHUB_INLINE_COMMENT_MAX_PER_TASK` | `10` | 单个任务最多发布 inline comment 数。 |
| `CODEPILOT_GITHUB_BOT_MENTION_ALIASES` | `@x-pilotx,@X-PilotX` | PR 评论命令 bot mention。 |
| `CODEPILOT_GITHUB_ALLOWED_COMMENT_AUTHOR_ASSOCIATIONS` | `OWNER,MEMBER,COLLABORATOR` | 允许触发 PR 命令的 GitHub author association。 |

支持命令：

```text
/review
@x-pilotx review
@x-pilotx 检查 SQL 风险并生成修复预览
@x-pilotx 查询团队规范和外部资料后解释这个问题
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
| `CODEPILOT_GITHUB_FIX_ENABLED` | `false` | 是否允许 Agent 调用 `startFixPreview` dry-run 修复预览 Tool。 |
| `CODEPILOT_GITHUB_FIX_PUBLISH_ENABLED` | `false` | 是否允许通过精确用户审批创建 bot 分支并发布修复 PR。 |
| `CODEPILOT_GITHUB_FIX_MERGE_ENABLED` | `false` | 是否允许通过第二次独立审批合并修复 PR。 |
| `CODEPILOT_GITHUB_FIX_APPROVAL_REQUIRE_SAME_USER` | `true` | 发布/合并审批是否必须由最初请求修复预览的用户发出。 |
| `CODEPILOT_GITHUB_FIX_MAX_PATCH_CHARS` | `100000` | 内部审批 patch payload 的最大字符数。 |
| `CODEPILOT_GITHUB_FIX_BRANCH_PREFIX` | `codepilot/fix-` | 修复分支前缀；系统会追加任务 ID 和随机后缀。 |
| `CODEPILOT_GITHUB_FIX_MERGE_METHOD` | `squash` | GitHub 合并方法，仅允许 `merge`、`squash` 或 `rebase`。 |
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

三个开关相互独立且默认关闭。发布审批只接受 `批准发布修复 <previewTaskId>` / `approve fix preview <id>`；合并必须再使用 `批准合并修复 <publishTaskId>` / `approve merge fix <id>`。Agent Tool Call 不是授权，服务端始终以持久化的原始 GitHub 评论为准。

## 命令任务与 Agent Runtime

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CODEPILOT_COMMAND_EXECUTION_LEASE` | `15m` | 命令任务单次执行租约；过期后可恢复。 |
| `CODEPILOT_COMMAND_OUTBOX_BATCH_SIZE` | `20` | 每次命令 outbox 发布批次。 |
| `CODEPILOT_COMMAND_OUTBOX_LOCK_TIMEOUT` | `2m` | 命令 outbox 发布锁超时；超时后消息可被其他实例重新领取。 |
| `CODEPILOT_COMMAND_OUTBOX_CONFIRM_TIMEOUT` | `5s` | 等待 RabbitMQ Publisher Confirm 的时间。 |
| `CODEPILOT_COMMAND_OUTBOX_RETRY_INITIAL_DELAY` | `1s` | 命令消息首次重试延迟。 |
| `CODEPILOT_COMMAND_OUTBOX_RETRY_MAX_DELAY` | `1m` | 命令消息指数退避上限。 |
| `CODEPILOT_COMMAND_OUTBOX_MAX_ATTEMPTS` | `10` | 命令消息可靠投递最大重试次数。 |
| `CODEPILOT_COMMAND_RECOVERY_BATCH_SIZE` | `50` | 单次租约恢复任务数。 |
| `CODEPILOT_AGENT_RESUMABLE_ENABLED` | `true` | 是否允许异步 Workflow 完成后恢复 Agent。 |
| `CODEPILOT_AGENT_MAX_MODEL_TURNS` | `8` | 单次 Agent run 最大模型轮次。 |
| `CODEPILOT_AGENT_MAX_TOOL_CALLS` | `12` | 单次 Agent run 最大 Tool 调用数。 |
| `CODEPILOT_AGENT_MAX_CONTINUATIONS` | `4` | 单次 Agent run 最大异步恢复次数。 |
| `CODEPILOT_AGENT_RUN_DEADLINE` | `30m` | Agent run 生命周期截止时间。 |
| `CODEPILOT_AGENT_CONTINUATION_CONTEXT_MAX_CHARS` | `4000` | 异步 Workflow 结果回注 continuation context 时的截断长度。 |
| `CODEPILOT_AGENT_WAITING_POLL_BATCH_SIZE` | `50` | 单次扫描恢复的 `WAITING_WORKFLOW` Agent run 数量上限。 |
| `CODEPILOT_AGENT_CONVERSATION_MAX_TURNS` | `5` | 同一 PR 注入的历史 Agent 对话轮数。 |
| `CODEPILOT_AGENT_CONVERSATION_MAX_CHARS` | `6000` | 注入的历史 Agent 对话总字符上限，运行时下限为 `500`。 |

## 死信队列运维

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CODEPILOT_DLQ_CONSUMER_ENABLED` | `true` | 是否消费 DLQ 并把死信落库到 `dead_letter_message`。**默认开启**——关闭后消息进了 DLQ 就只留在队列里，无法查询与重放。 |
| `CODEPILOT_DLQ_REPLAY_ENABLED` | `false` | 是否开放重放接口 `POST /api/admin/dead-letters/{id}/replay`。**默认关闭**：重放会把手上有副作用的消息重新投回主链路，属显式运维动作。 |
| `CODEPILOT_DLQ_RETENTION_DAYS` | `30` | 死信记录保留天数，超期由维护任务清理。 |
| `CODEPILOT_DLQ_PAYLOAD_MAX_CHARS` | `4000` | 落库前 payload 的最大字符数，超出截断。 |
| `CODEPILOT_DLQ_MAINTENANCE_INTERVAL_MILLIS` | `30000` | 积压指标刷新与过期清理的间隔。 |

**消费顺序**：死信消费者使用**手动 ack** 的容器工厂，必须先把消息成功落库、再 ack。落库失败时不 ack，消息留在 DLQ 中；`payload_digest + status` 的唯一索引保证重复投递只累加 `attempt_count`，不会产生重复行。宁可有重复记录，也不丢消息。

**积压指标**：`codepilot.dlq.backlog`（gauge）反映 `dead_letter_message` 中 `status='PENDING'` 的数量。该指标取自数据库而非 RabbitMQ 管理接口，因此不引入额外的管理连接；刷新失败只记 warning，不影响主链路。

**运维接口**（受 `/api/**` 的 API Key 过滤器保护）：

| 接口 | 说明 |
| --- | --- |
| `GET /api/admin/dead-letters` | 分页查询，支持 `status` / `sourceQueue` 过滤，返回体含 `pendingCount` |
| `GET /api/admin/dead-letters/{id}` | 查看单条死信详情 |
| `POST /api/admin/dead-letters/{id}/replay` | 重新投递（需 `CODEPILOT_DLQ_REPLAY_ENABLED=true`）。重放安全：目标任务走既有的 claim + execution token 入口，重复投递不会重复执行 |
| `POST /api/admin/dead-letters/{id}/discard` | 标记为已处理 |

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

## SearXNG 外部检索 Tool

默认关闭。启用后，只有配置的 SearXNG 服务会被访问；Agent 只接收截断后的 HTTPS 搜索结果，网页内容仍然视为不可信外部参考。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CODEPILOT_SEARXNG_ENABLED` | `false` | 是否开放 `searchWeb` Agent Tool。 |
| `CODEPILOT_SEARXNG_BASE_URL` | 空 | 已部署的 SearXNG 服务地址，例如 `http://localhost:8080`。 |
| `CODEPILOT_SEARXNG_TIMEOUT` | `5s` | 单次搜索连接/读取超时。 |
| `CODEPILOT_SEARXNG_MAX_RESULTS` | `5` | 单次最多返回的结果数。 |
| `CODEPILOT_SEARXNG_MAX_QUERY_CHARS` | `240` | 模型提交的查询最大长度。 |
| `CODEPILOT_SEARXNG_MAX_RESULT_CHARS` | `1200` | 单条搜索摘要最大长度。 |
| `CODEPILOT_SEARXNG_ALLOWED_DOMAINS` | 空 | 可选域名白名单，逗号分隔；为空时接受 HTTPS 结果。 |
| `CODEPILOT_SEARXNG_DOCUMENT_FETCH_ENABLED` | `false` | 是否允许抓取搜索结果正文。 |
| `CODEPILOT_SEARXNG_DOCUMENT_FETCH_TIMEOUT` | `8s` | 正文抓取超时。 |
| `CODEPILOT_SEARXNG_DOCUMENT_FETCH_MAX_BYTES` | `262144` | 单个网页响应最大字节数。 |
| `CODEPILOT_SEARXNG_DOCUMENT_FETCH_MAX_CHARS` | `12000` | 单个网页注入 Agent 的最大字符数。 |

正文抓取要求 `CODEPILOT_SEARXNG_ALLOWED_DOMAINS` 非空；URL 必须先由当前 Agent run 的 `searchWeb` 返回，服务端还会阻止私网/环回 DNS、重定向、非文本 Content-Type 和超限响应。

## 审查限制与文件级并发

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `CODEPILOT_REVIEW_MAX_FILES_PER_TASK` | `30` | 单个任务最多进入 AI Review 的文件数。 |
| `CODEPILOT_REVIEW_MAX_SUMMARY_FINDINGS` | `20` | Summary Comment 最多展示问题数。 |
| `CODEPILOT_REVIEW_COVERAGE_REPORT_ENABLED` | `true` | 是否在摘要中输出「审查覆盖」区块，说明哪些文件未被审查及原因。**默认开启**：关闭会让"只审了 5 / 200 个文件"的摘要看起来像完整结论。 |
| `CODEPILOT_REVIEW_COVERAGE_MAX_LISTED_FILES` | `20` | 覆盖度区块中逐个列出的未审查文件上限，超出部分折叠进 `<details>`。 |
| `CODEPILOT_REVIEW_MIN_COVERAGE_RATIO` | `0.5` | 已审文件数 / 变更文件数低于此比例时，摘要顶部给出醒目告警。`0` 表示关闭告警。 |
| `CODEPILOT_REVIEW_FINDING_MEMORY_ENABLED` | `false` | 是否启用跨 headSha 的 finding 记忆。**默认关闭**：它会让上次已提过的问题不再重复评论，属于发布行为改变，需要灰度。开启后下面两项才生效。 |
| `CODEPILOT_REVIEW_SUPPRESS_REPEATED_FINDINGS` | `true` | 记忆中已存在的 finding 是否跳过再次评论。被跳过的 finding **仍会落库并在摘要中计入"已抑制"**，不会静默消失。 |
| `CODEPILOT_REVIEW_ALLOW_RESOLUTION` | `true` | 是否允许把"本次未再现"的 finding 判为已修复。**即使开启也有两道额外约束**：本次必须是完整审查（无预算跳过、无文件级 LLM 失败），且 head sha 确实变化。详见「审查记忆的安全边界」。 |
| `CODEPILOT_REVIEW_DISMISSAL_SYNC_ENABLED` | `false` | 是否读取 GitHub review thread 的 resolved 状态做误报抑制（R7）。**默认关闭**：需要 GraphQL 权限，且会改变"提不提某个问题"的行为。 |
| `CODEPILOT_REVIEW_MULTILINE_COMMENT_ENABLED` | `true` | 是否允许把 finding 发布成**多行区间**评论（R4）。默认开启；区间不合法时自动降级为单行，不会丢失 finding。 |

**多行区间评论（R4）**：一个 finding 常跨多行。开启后，若模型给出了 `startLine` / `endLine`，系统会尝试发布 `start_line..line` 的区间评论。

**降级而非丢弃**：GitHub 对区间有硬约束——起止必须位于 diff **同一侧**、**都在 diff 内**、且构成**连续**一段。`DiffLineMapper.mapRange` 会在本地校验这三条，任何一条不满足就**降级为单行评论**，绝不因此丢掉这个 finding。区间跨 hunk 同样降级。

**单行路径不受影响**：没有区间的 finding 走的调用形态与引入本能力之前**完全一致**，避免"新增能力顺手改动原有行为"。

**误报抑制（R7）**：维护者在 GitHub 上把一个**由本机器人发起**的 review thread resolve 掉，系统就把它对应的 finding 记为 `DISMISSED`，后续审查不再重复提。

两条硬约束：

1. **只认"首条评论由本机器人发出"的线程**。任何人都能开一个线程再 resolve 掉它；如果照单全收，等于让人用 resolve 来关闭我们的 finding。
2. **任何失败都降级为"不抑制"**。GraphQL 权限不足、限流、网络抖动都只记一条 warning —— 这是旁路能力，**绝不能让审查失败**。拿不到机器人登录名时同样一条都不抑制。

线程与 finding 的关联靠行内评论正文里的隐藏标记 `<!-- codepilot-finding:<fingerprint> -->`（不含行号，跨 push 稳定）。**取不到该标记的线程一律跳过**——绝不退化成用行号或标题去猜，那会把不相干的线程关联到错误的 finding 上。

被抑制的 finding **仍然落库并在摘要中计入"已抑制 N 条"**，不会静默消失。

**审查记忆的安全边界**：`RESOLVED` 的判定只有同时满足以下条件才会发生——否则一个"没再出现"的问题可能只是**因为它所在的文件根本没被审到**，把它标成"已修复"是错误结论：

1. 本次审查**完整**：没有文件因 `max-files-per-task` / `max-total-patch-chars` 等预算被跳过，也没有文件级 LLM 失败（`AI_REVIEW_FAILED`）
2. **head sha 与上次不同**：没有新提交时，"未再现"不能作为已修复的证据

**指纹为什么不含行号**：行号会随 rebase 或前面插入几行而漂移，含行号会让去重立刻失效。指纹只用 `文件路径 + 问题类型 + 归一化标题`，另有基于建议代码的第二指纹做 OR 匹配。**识别信息不足时指纹为 null，此时永不抑制**——宁可重复评论，也不要把不同问题误判成同一个而漏报。

**关于审查覆盖度**：只要还有变更文件未被审查，摘要就**不会**输出"未发现问题"，而是明确说明"本次没有可报告的问题，但有 N 个文件未被审查"。这条约束不受 `CODEPILOT_REVIEW_COVERAGE_REPORT_ENABLED` 影响——即使关闭覆盖度区块，"未能审查任何变更文件"的告警依然会输出。
| `CODEPILOT_REVIEW_MAX_PATCH_CHARS_PER_FILE` | `12000` | 单文件 patch 字符上限。 |
| `CODEPILOT_REVIEW_MAX_TOTAL_PATCH_CHARS` | `80000` | 单任务 patch 总字符上限。 |
| `CODEPILOT_REVIEW_MAX_PARALLEL_FILES` | `2` | 单个任务内文件级并发数。 |
| `CODEPILOT_REVIEW_MAX_REPO_CONTEXT_FILES` | `6` | 最多召回相关仓库上下文文件数。 |
| `CODEPILOT_REVIEW_MAX_REPO_CONTEXT_FILE_CHARS` | `20000` | 单个相关源文件最大读取字符数。 |
| `CODEPILOT_REVIEW_MAX_REPO_CONTEXT_EXCERPT_CHARS` | `900` | 注入 prompt 的单片段上限。 |
| `CODEPILOT_REVIEW_EXECUTION_LEASE` | `15m` | 消费者执行租约；进程异常退出后，过期任务可重新领取。 |
| `CODEPILOT_REVIEW_OUTBOX_BATCH_SIZE` | `20` | 本地消息和 GitHub 发布任务的单次领取数量。 |
| `CODEPILOT_REVIEW_OUTBOX_LOCK_TIMEOUT` | `2m` | 本地消息发布锁的超时时间。 |
| `CODEPILOT_REVIEW_OUTBOX_CONFIRM_TIMEOUT` | `5s` | RabbitMQ Publisher Confirm 等待时间。 |
| `CODEPILOT_REVIEW_OUTBOX_RETRY_INITIAL_DELAY` | `1s` | 本地消息首次重试延迟。 |
| `CODEPILOT_REVIEW_OUTBOX_RETRY_MAX_DELAY` | `1m` | 本地消息指数退避上限。 |
| `CODEPILOT_REVIEW_OUTBOX_MAX_ATTEMPTS` | `10` | 本地消息发布最大尝试次数，耗尽后保留失败记录供人工补偿。 |
| `CODEPILOT_REVIEW_PUBLICATION_MAX_ATTEMPTS` | `5` | GitHub 评论发布最大尝试次数。 |
| `CODEPILOT_REVIEW_OUTBOX_POLL_INTERVAL_MILLIS` | `1000` | 本地消息发布轮询间隔。 |
| `CODEPILOT_REVIEW_EXECUTION_RECOVERY_INTERVAL_MILLIS` | `30000` | 过期执行租约扫描间隔。 |
| `CODEPILOT_REVIEW_PUBLICATION_POLL_INTERVAL_MILLIS` | `5000` | GitHub 评论发布重试扫描间隔。 |

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
