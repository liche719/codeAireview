# API 文档

除 `POST /api/github/webhook` 外，默认所有 `/api/**` 接口都需要内部 API Key：

```bash
X-CodePilot-Api-Key: <CODEPILOT_API_AUTH_API_KEY>
```

如果未配置 `CODEPILOT_API_AUTH_API_KEY`，受保护接口会返回 `401`，避免公开环境裸奔。GitHub Webhook 入口不使用该 header，它依赖 `X-Hub-Signature-256` 做 GitHub HMAC 验签。

## `POST /api/reviews`

创建 PR 审查任务。

请求体：

```json
{
  "prUrl": "https://github.com/owner/repo/pull/123"
}
```

返回示例：

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

## 认证失败返回

```json
{
  "code": 401,
  "message": "invalid or missing API key",
  "data": null
}
```

## `GET /api/reviews/{taskId}`

查看单个审查任务详情。

## `GET /api/reviews/{taskId}/files`

查看 PR changed files 的保存结果。

## `GET /api/reviews/{taskId}/issues`

查看审查问题列表。

## `POST /api/rules`

创建规范文档。

请求体：

```json
{
  "title": "MySQL SQL 编写规范",
  "type": "SQL_RULE",
  "source": "manual",
  "content": "禁止字符串拼接 SQL。"
}
```

## `POST /api/rules/{id}/index`

对规范文档进行切片并向量化，写入 `rule_chunk`。

## `POST /api/rules/search`

检索相关规范。

请求体：

```json
{
  "query": "代码中通过字符串拼接 SQL 查询用户信息",
  "topK": 3,
  "type": "SQL_RULE"
}
```

## `POST /api/github/webhook`

接收 GitHub Pull Request Webhook。

该接口不需要 `X-CodePilot-Api-Key`，但必须使用 GitHub Webhook Secret 对 `X-Hub-Signature-256` 验签。除非只用于可信本地调试，不要开启跳过验签。

请求头：

- `X-GitHub-Event`
- `X-GitHub-Delivery`
- `X-Hub-Signature-256`

支持事件：

- `pull_request` / `opened`
- `pull_request` / `synchronize`
- `pull_request` / `reopened`
