# CodePilot AI Review

CodePilot AI Review 是一个基于 Spring Boot 的 GitHub PR 智能代码评审后端项目。

当前第一阶段已经跑通主线：

1. 提交 GitHub PR 链接。
2. 解析 `owner`、`repo` 和 `pull request number`。
3. 创建 `review_task` 记录。
4. 发送任务 ID 到 RabbitMQ。
5. 消费消息并拉取 PR changed files。
6. 保存 `review_file`。
7. 通过基础 AI Review 生成结构化问题并保存到 `review_issue`。
8. 更新任务状态和风险等级。

## 技术栈

- Spring Boot 3.x
- MyBatis Plus
- PostgreSQL
- pgvector
- Redis
- RabbitMQ
- Knife4j / OpenAPI
- Lombok

## 快速开始

启动前请先确认 Docker Desktop 已经运行。

```bash
docker compose up -d
mvn spring-boot:run
```

Swagger UI：

```text
http://localhost:8080/doc.html
```

创建审查任务：

```bash
curl -X POST http://localhost:8080/api/reviews \
  -H "Content-Type: application/json" \
  -d "{\"prUrl\":\"https://github.com/owner/repo/pull/123\"}"
```

消费者会通过 GitHub REST API 拉取 PR changed files，并保存到 `review_file`。

如果是私有仓库，或者你需要更高的 GitHub API 限流额度，可以配置：

```bash
# PowerShell
$env:CODEPILOT_GITHUB_TOKEN="your_github_token"

# cmd
set CODEPILOT_GITHUB_TOKEN=your_github_token
```

## 可用接口

```text
POST /api/reviews
GET  /api/reviews/{taskId}
GET  /api/reviews/{taskId}/files
GET  /api/reviews/{taskId}/issues
GET  /api/github/pulls/files?owner=owner&repo=repo&pullNumber=123
```

## 说明

- 当没有配置 LLM API Key 时，系统仍可正常启动和处理任务，只是会跳过 AI Review。
- 当前项目重点是后端主线，不包含前端页面。
- 后续可继续扩展 RAG、Tool Calling 和更完整的审查能力。
