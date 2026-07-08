# 本地演示流程

这份文档用于把 CodePilot AI 跑成一个可展示、可截图、可复现的项目，而不是只停留在 README 描述。

## 方案 A：本地 smoke 演示

适合快速证明项目能启动、鉴权有效、数据库可写、API 可访问。

### 1. 启动依赖

```powershell
docker compose up -d
```

### 2. 启动应用

```powershell
copy .env.example .env
mvn spring-boot:run
```

如果使用脚本：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/start-local.ps1
```

### 3. 运行 smoke 脚本

另开一个终端：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-local.ps1
```

脚本会检查：

- `GET /v3/api-docs` 能访问。
- `GET /api/reviews` 不带 API Key 会返回 `401`。
- 带 `X-CodePilot-Api-Key` 能访问受保护接口。
- `POST /api/rules` 能创建一条规则文档。
- `GET /api/rules` 能读回规则文档。
- 响应里能看到限流相关 header。

### 4. 建议截图/日志

用于 GitHub PR、简历附件或面试展示：

- smoke 脚本成功输出。
- RabbitMQ 管理页面 `http://localhost:15672` 的 queue 列表。
- Swagger UI：`http://localhost:8080/swagger-ui/index.html`。
- 数据库里的 `review_task`、`review_file`、`review_issue` 或 `rule_document` 记录。

### 5. 可选：并发 Webhook smoke

适合回答“高并发处理在哪里”。先在 `.env` 开启 webhook：

```env
CODEPILOT_GITHUB_WEBHOOK_ENABLED=true
CODEPILOT_GITHUB_WEBHOOK_SECRET=change-me-webhook-secret
```

如果设置了仓库白名单，确保包含脚本目标仓库：

```env
CODEPILOT_GITHUB_ALLOWED_REPOSITORIES=liche719/codeAireview
```

应用启动后运行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/concurrency-smoke.ps1 -RequestCount 16
```

预期输出类似：

```text
CodePilot webhook concurrency smoke
Requests: 16

Concurrency smoke passed.
Processed requests: 1
Duplicate events ignored: 15
Task id: 123
```

这证明同一个 PR/headSha 在并发 webhook 进入时只会有一个请求继续创建或复用任务，其余请求被 Redis 幂等键拦截，避免 GitHub 重试或重复 delivery 造成重复审查。

## 方案 B：离线审查质量评估

适合证明不是“人工点一点”的 demo，而是有可回归的质量基线。

```powershell
mvn "-Dtest=AiReviewPipelineEvalTest,DeterministicReviewEvalTest,PromptRegressionEvalTest" test
```

或者运行综合可靠性基线：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-review-reliability-baseline.ps1
```

该评估不调用真实 LLM，会 replay 测试夹具里的 PR-like diff，覆盖 SQL 风险、Secret、prompt injection、缺少测试、API 契约变化、配置安全回归和非法 JSON 降级。

## 方案 C：真实 GitHub PR 演示

适合完整展示 Webhook、RabbitMQ、GitHub 评论回写和 inline comment。

### 1. 准备 GitHub 凭证

本地 `.env` 至少设置：

```env
CODEPILOT_API_AUTH_API_KEY=change-me-local-dev-key
CODEPILOT_GITHUB_AUTH_MODE=pat
CODEPILOT_GITHUB_TOKEN=<fine-grained token>
CODEPILOT_GITHUB_COMMENT_ENABLED=true
CODEPILOT_GITHUB_INLINE_COMMENT_ENABLED=true
CODEPILOT_GITHUB_INLINE_COMMENT_MAX_PER_TASK=10
CODEPILOT_GITHUB_WEBHOOK_ENABLED=true
CODEPILOT_GITHUB_WEBHOOK_SECRET=<random secret>
CODEPILOT_GITHUB_ALLOWED_REPOSITORIES=owner/repo
CODEPILOT_LLM_API_KEY=<model api key>
CODEPILOT_EMBEDDING_API_KEY=<embedding api key>
```

生产或长期部署推荐使用 GitHub App，见 [github-auth.md](github-auth.md)。

### 2. 暴露本地服务

```powershell
ngrok http 8080
```

### 3. 配置 GitHub Webhook

在目标仓库 `Settings -> Webhooks` 新增：

```text
Payload URL: https://<ngrok-domain>/api/github/webhook
Content type: application/json
Secret: 与 CODEPILOT_GITHUB_WEBHOOK_SECRET 一致
Events: Pull requests, Issue comments
```

### 4. 创建一个测试 PR

可以在测试仓库里制造一些明显风险：

```java
String sql = "select * from user where name = '" + name + "'";
String token = "abc123-secret";
```

PR 打开或同步后，CodePilot 会：

1. 接收 Webhook。
2. Redis 去重。
3. 创建 `review_task`。
4. 投递 RabbitMQ。
5. 拉取 changed files 和 diff。
6. 执行 deterministic rules、RAG、LLM 审查。
7. 保存 `review_issue`。
8. 回写 GitHub Summary Comment / inline comment。

### 5. 手动触发命令

在 PR Conversation 评论：

```text
/review
```

或：

```text
@x-pilotx review
```

如果开启 fix 模式，还可以演示：

```text
@x-pilotx fix dry-run
```

`@x-pilotx fix` 默认关闭。只有在隔离环境、最小权限 token 和 sandbox 校验准备好后才建议开启。

## 演示时重点讲什么

- “我不是直接把 diff 塞给模型，而是先做任务编排、文件规划、风险识别和规则召回。”
- “审查执行有两层并发：RabbitMQ 任务级并发和单任务内文件级并发。”
- “LLM 输出不会直接发布，必须经过 patch verification 和 location guard。”
- “GitHub 评论有 marker/fingerprint，避免重复刷屏。”
- “失败路径有 retry、DLQ、脱敏日志和任务状态记录。”

## 常见问题

### Smoke 脚本 401

检查应用启动时使用的 `CODEPILOT_API_AUTH_API_KEY` 是否与脚本参数一致：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-local.ps1 -ApiKey your-key
```

### Webhook 一直 ignored

检查：

- `CODEPILOT_GITHUB_WEBHOOK_ENABLED=true`
- Webhook secret 与 GitHub 页面配置一致。
- `CODEPILOT_GITHUB_ALLOWED_REPOSITORIES` 是否包含当前仓库。
- GitHub Webhook delivery 页面里的 response body。

### inline comment 没出现

可能原因：

- `CODEPILOT_GITHUB_INLINE_COMMENT_ENABLED=false`
- issue 无法定位到 changed diff line。
- 超过 `CODEPILOT_GITHUB_INLINE_COMMENT_MAX_PER_TASK`。
- GitHub token/App 缺少 Pull requests write 权限。

这不是失败，系统仍会通过 Summary Comment 展示结果。
