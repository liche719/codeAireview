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

`pull_request` 事件会创建审查任务。`issue_comment` 事件只处理 PR Conversation 中的命令。

命令：

```text
/review
@x-pilotx review
@x-pilotx fix dry-run
@x-pilotx fix
```

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
