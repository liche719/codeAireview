# API 文档

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

请求头：

- `X-GitHub-Event`
- `X-GitHub-Delivery`
- `X-Hub-Signature-256`

支持事件：

- `pull_request` / `opened`
- `pull_request` / `synchronize`
- `pull_request` / `reopened`
