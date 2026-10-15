# API 文档

除 `POST /api/github/webhook` 外，默认所有 `/api/**` 接口都需要内部 API Key：

```http
X-CodePilot-Api-Key: <CODEPILOT_API_AUTH_API_KEY>
```

如果未配置 `CODEPILOT_API_AUTH_API_KEY`，受保护接口会返回 `401`。Webhook 入口不使用该 header，它依赖 `X-Hub-Signature-256` 做 GitHub HMAC 验签。

默认 `/api/**` 还会经过固定窗口限流。触发限流时返回 `429`，并带：

- `Retry-After`
- `X-RateLimit-Limit`
- `X-RateLimit-Remaining`
- `X-RateLimit-Reset`

## 通用响应

成功：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

鉴权失败：

```json
{
  "code": 401,
  "message": "invalid or missing API key",
  "data": null
}
```

限流：

```json
{
  "code": 429,
  "message": "API rate limit exceeded",
  "data": null
}
```

## Review API

### `POST /api/reviews`

创建 PR 审查任务。

请求：

```json
{
  "prUrl": "https://github.com/owner/repo/pull/123"
}
```

返回：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "taskId": 123,
    "status": "PENDING"
  }
}
```

PowerShell 示例：

```powershell
curl -X POST http://localhost:8080/api/reviews ^
  -H "X-CodePilot-Api-Key: change-me-local-dev-key" ^
  -H "Content-Type: application/json" ^
  -d "{\"prUrl\":\"https://github.com/owner/repo/pull/123\"}"
```

### `GET /api/reviews`

分页查询审查任务。

Query 参数：

- `current`：页码，默认 `1`。
- `size`：每页数量，默认 `10`。

```powershell
curl "http://localhost:8080/api/reviews?current=1&size=10" ^
  -H "X-CodePilot-Api-Key: change-me-local-dev-key"
```

### `GET /api/reviews/{taskId}`

查询单个审查任务详情。

```powershell
curl http://localhost:8080/api/reviews/123 ^
  -H "X-CodePilot-Api-Key: change-me-local-dev-key"
```

### `GET /api/reviews/{taskId}/files`

查询该任务保存的 changed file 记录。

```powershell
curl http://localhost:8080/api/reviews/123/files ^
  -H "X-CodePilot-Api-Key: change-me-local-dev-key"
```

### `GET /api/reviews/{taskId}/issues`

查询该任务的审查问题。

```powershell
curl http://localhost:8080/api/reviews/123/issues ^
  -H "X-CodePilot-Api-Key: change-me-local-dev-key"
```

### `GET /api/reviews/{taskId}/linked-issues`

查询当前 PR 在 GitHub 上关联的 closing issues。系统优先使用 GitHub GraphQL `closingIssuesReferences`，如果不可用或为空，则解析 PR body 里的 `fixes #123`、`closes owner/repo#123`、`resolves https://github.com/owner/repo/issues/123`。

```powershell
curl http://localhost:8080/api/reviews/123/linked-issues ^
  -H "X-CodePilot-Api-Key: change-me-local-dev-key"
```

返回示例：

```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "repositoryOwner": "liche719",
      "repositoryName": "codeAireview",
      "number": 12,
      "title": "Fix SQL review false positive",
      "state": "OPEN",
      "htmlUrl": "https://github.com/liche719/codeAireview/issues/12",
      "linkSource": "GRAPHQL_CLOSING_ISSUES"
    }
  ]
}
```

## Rule / RAG API

### `POST /api/rules`

创建规则文档。

请求：

```json
{
  "title": "MySQL SQL 编写规范",
  "type": "SQL_RULE",
  "source": "manual",
  "content": "禁止字符串拼接 SQL。禁止 SELECT *。UPDATE 和 DELETE 必须带 WHERE。"
}
```

示例：

```powershell
curl -X POST http://localhost:8080/api/rules ^
  -H "X-CodePilot-Api-Key: change-me-local-dev-key" ^
  -H "Content-Type: application/json" ^
  -d "{\"title\":\"MySQL SQL 编写规范\",\"type\":\"SQL_RULE\",\"source\":\"manual\",\"content\":\"禁止字符串拼接 SQL。禁止 SELECT *。UPDATE 和 DELETE 必须带 WHERE。\"}"
```

### `GET /api/rules`

查询规则文档列表。

```powershell
curl http://localhost:8080/api/rules ^
  -H "X-CodePilot-Api-Key: change-me-local-dev-key"
```

### `GET /api/rules/{id}`

查询规则文档详情。

```powershell
curl http://localhost:8080/api/rules/1 ^
  -H "X-CodePilot-Api-Key: change-me-local-dev-key"
```

### `POST /api/rules/{id}/index`

对规则文档切片、向量化，并写入 `rule_chunk`。

```powershell
curl -X POST http://localhost:8080/api/rules/1/index ^
  -H "X-CodePilot-Api-Key: change-me-local-dev-key"
```

注意：该接口需要可用的 embedding 配置。

### `POST /api/rules/search`

检索相关规则。

请求：

```json
{
  "query": "代码中通过字符串拼接 SQL 查询用户信息",
  "topK": 3,
  "type": "SQL_RULE"
}
```

```powershell
curl -X POST http://localhost:8080/api/rules/search ^
  -H "X-CodePilot-Api-Key: change-me-local-dev-key" ^
  -H "Content-Type: application/json" ^
  -d "{\"query\":\"代码中通过字符串拼接 SQL 查询用户信息\",\"topK\":3,\"type\":\"SQL_RULE\"}"
```

## 死信运维 API

最终消费失败的消息会被死信消费者落库到 `dead_letter_message`，可通过以下接口查询与处理。
这些接口同样需要 API Key（路径匹配 `/api/**`）。详见 [环境变量](env.md) 的「死信队列运维」一节。

### `GET /api/admin/dead-letters`

分页查询死信。Query 参数：`status`（`PENDING` / `REPLAYED` / `DISCARDED`）、`sourceQueue`、`current`、`size`。

返回体除分页记录外还带 `pendingCount`，可直接用于告警。

```powershell
curl "http://localhost:8080/api/admin/dead-letters?status=PENDING&current=1&size=20" ^
  -H "X-CodePilot-Api-Key: change-me-local-dev-key"
```

### `GET /api/admin/dead-letters/{id}`

查看单条死信详情（含脱敏后的 payload 与 digest）。不存在时返回 `404`。

### `POST /api/admin/dead-letters/{id}/replay`

把死信重新投回主链路。**默认关闭**，需设置 `CODEPILOT_DLQ_REPLAY_ENABLED=true`；关闭时返回 `403`。

重放是幂等的：目标任务走既有的 claim + execution token 入口，重复投递不会重复执行。

无法从 payload 解析出任务 id 时返回 `422`。

### `POST /api/admin/dead-letters/{id}/discard`

把死信标记为已处理（`DISCARDED`）。不存在时返回 `404`。

## GitHub 工具 API

这两个接口是运维/调试用的只读工具，不参与审查主链路。

### `POST /api/github/parse-pr`

解析 PR URL，返回 owner / repo / pullNumber。

```json
{ "prUrl": "https://github.com/owner/repo/pull/123" }
```

### `GET /api/github/pulls/files`

直接查询某个 PR 的 changed files（含 patch）。

Query 参数：`owner`、`repo`、`pullNumber`（均必填）。

```powershell
curl "http://localhost:8080/api/github/pulls/files?owner=liche719&repo=codeAireview&pullNumber=12" ^
  -H "X-CodePilot-Api-Key: change-me-local-dev-key"
```

## GitHub Webhook API

### `POST /api/github/webhook`

接收 GitHub Webhook。

请求头：

- `X-GitHub-Event`
- `X-GitHub-Delivery`
- `X-Hub-Signature-256`

支持事件：

- `pull_request` / `opened`
- `pull_request` / `synchronize`
- `pull_request` / `reopened`
- `issue_comment` / `created`

`pull_request` 事件会创建审查任务。`issue_comment` 事件处理 `/review` 兼容命令和任意 `@x-pilotx ...` Agent 请求。

命令：

```text
/review
@x-pilotx 总结当前审查发现并给出证据
@x-pilotx 查一下 Redis 最新建议，再检查这个 PR
@x-pilotx 针对 UserService 的空指针问题生成修复预览
```

所有 mention 都创建 `CHAT` Agent 任务，不在 Webhook 入口预分类。Agent 根据完整请求选择多个 Tool，可启动审查 Workflow 和 dry-run 修复预览。模型不能直接创建分支、push、创建 PR 或 merge；这些写动作只能由服务端审批门禁派生的 `FIX_PUBLISH` / `FIX_MERGE` 异步任务执行。

Agent 运行具有有限上下文和可恢复状态。同步 Tool 会在同一次模型调用内继续循环；异步 Review/Fix Tool 会返回 scheduled 状态，任务进入 `WAITING_WORKFLOW`，子任务完成后恢复原 Agent run。命令 outbox 和 execution lease 负责消息可靠投递与重复消费保护。

修复发布和合并使用两个独立的精确命令：

```text
@x-pilotx 批准发布修复 <previewTaskId>
@x-pilotx 批准合并修复 <publishTaskId>
```

也支持英文命令 `approve fix preview <id>` 和 `approve merge fix <id>`。额外说明或 ID 不匹配会被拒绝；模型调用 Tool 本身不构成授权。服务端会读取持久化的原始评论并验证评论者、仓库、PR、source head SHA、patch hash、允许修改路径和功能开关。

返回 processed：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "taskId": 123,
    "action": "opened",
    "reason": null
  }
}
```

返回 ignored：

```json
{
  "code": 0,
  "message": "ignored",
  "data": {
    "taskId": null,
    "action": "closed",
    "reason": "unsupported pull_request action"
  }
}
```

不要在生产环境开启 `CODEPILOT_GITHUB_WEBHOOK_SKIP_SIGNATURE_WHEN_SECRET_EMPTY=true`。
