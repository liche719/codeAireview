# CodePilot AI

CodePilot AI 是一个基于 Spring Boot、LangChain4j 和 GitHub API 的 PR 智能代码审查后端，支持 Webhook 自动触发、手动创建审查、RAG 编码规范检索、Agent 工具调用、评论回写和幂等更新。

## 项目背景

这个项目面向 Java 后端工程场景，目标不是做一个“会聊天的模型壳子”，而是把 GitHub PR 审查、团队规范、确定性规则检测和结果回写串成一条可落地的工程闭环。它适合作为简历主项目，因为它同时覆盖了消息队列、数据库、向量检索、Webhook、AI Agent 和 GitHub 集成。

## 核心功能

- GitHub Webhook 自动触发 PR 审查。
- 手动提交 PR 链接创建审查任务。
- RabbitMQ 异步消费审查任务。
- GitHub API 拉取 PR changed files。
- PostgreSQL + pgvector 规范库检索。
- LangChain4j `@AiService` 做语义审查。
- LangChain4j `@Tool` 做 SQL 风险、敏感信息、单测建议检测。
- 审查结果入库到 `review_issue`。
- PR 顶部评论回写与幂等更新。

## 技术栈

- Spring Boot 3.5.x
- Spring Web / Spring Validation
- MyBatis Plus
- PostgreSQL + pgvector
- Redis
- RabbitMQ
- LangChain4j 1.15.0
- GitHub REST API
- Springdoc OpenAPI
- Docker Compose

## 系统架构

```text
GitHub PR / Webhook / 手动请求
        ↓
ReviewTask 创建
        ↓
RabbitMQ 异步消费
        ↓
GitHub API 拉取 changed files
        ↓
RAG 检索团队规范
        ↓
LangChain4j AI Review + Tool 调用
        ↓
review_issue 入库
        ↓
PR 顶部评论回写
```

## 核心流程

```text
Webhook opened/synchronize/reopened
        ↓
签名校验 + Redis 去重
        ↓
createTask(prUrl, title)
        ↓
发送 taskId 到 RabbitMQ
        ↓
消费者拉取 PR Diff
        ↓
RAG 召回规范 + Tool 检测
        ↓
LLM 输出 JSON
        ↓
保存 review_issue
        ↓
生成 Markdown 报告
        ↓
GitHub PR 评论更新或创建
```

## 本地启动

1. 启动依赖服务：

```bash
docker compose up -d
```

2. 启动应用：

```bash
mvn spring-boot:run
```

3. 打开接口文档：

```text
http://localhost:8080/doc.html
http://localhost:8080/swagger-ui/index.html
```

## 环境变量

- `CODEPILOT_GITHUB_TOKEN`：GitHub API Token，用于拉取 PR 文件、评论回写和评论更新。
- `CODEPILOT_GITHUB_COMMENT_ENABLED`：是否开启 GitHub PR 评论回写，默认 `false`。
- `CODEPILOT_GITHUB_WEBHOOK_ENABLED`：是否开启 GitHub Webhook 入口，默认 `false`。
- `CODEPILOT_GITHUB_WEBHOOK_SECRET`：GitHub Webhook 签名密钥。
- `CODEPILOT_GITHUB_WEBHOOK_SKIP_SIGNATURE_WHEN_SECRET_EMPTY`：本地调试用跳过验签开关，默认 `false`。
- `CODEPILOT_LLM_API_KEY`：LLM API Key。
- `CODEPILOT_LLM_BASE_URL`：LLM OpenAI-compatible 接口地址。
- `CODEPILOT_LLM_MODEL`：LLM 模型名。
- `CODEPILOT_EMBEDDING_API_KEY`：Embedding API Key。
- `CODEPILOT_EMBEDDING_BASE_URL`：Embedding 接口地址。
- `CODEPILOT_EMBEDDING_MODEL`：Embedding 模型名。
- `CODEPILOT_REVIEW_MAX_FILES_PER_TASK`：单个任务最多进入 AI Review 的文件数，默认 `30`。
- `CODEPILOT_REVIEW_MAX_PATCH_CHARS_PER_FILE`：单个文件 patch 最大字符数，默认 `12000`。
- `CODEPILOT_REVIEW_MAX_TOTAL_PATCH_CHARS`：单个任务累计进入 AI Review 的 patch 最大字符数，默认 `80000`。

## API 示例

创建手动审查任务：

```bash
curl -X POST http://localhost:8080/api/reviews ^
  -H "Content-Type: application/json" ^
  -d "{\"prUrl\":\"https://github.com/owner/repo/pull/123\"}"
```

查看任务详情：

```bash
curl http://localhost:8080/api/reviews/123
```

查看审查问题：

```bash
curl http://localhost:8080/api/reviews/123/issues
```

## Webhook 配置

在 GitHub 仓库 `Settings -> Webhooks` 中新增 Webhook：

- Payload URL: `https://xxx.ngrok-free.app/api/github/webhook`
- Content type: `application/json`
- Secret: 与 `CODEPILOT_GITHUB_WEBHOOK_SECRET` 保持一致
- Events: `Pull requests`

Webhook 支持 `opened`、`synchronize`、`reopened` 三类 PR 事件。

出于安全考虑，Webhook 默认关闭。演示或部署时需要设置 `CODEPILOT_GITHUB_WEBHOOK_ENABLED=true`，并建议配置 `CODEPILOT_GITHUB_WEBHOOK_SECRET`。

## RAG 使用

规范文档通过 `POST /api/rules` 创建，随后通过 `POST /api/rules/{id}/index` 切片并向量化，存入 `rule_chunk`。审查时会根据 PR Diff 召回相关规范片段，并注入 AI Review Prompt。

## 评论回写

审查成功后，系统会生成 Markdown 报告并回写到 PR 顶部评论。评论带有 marker `<!-- codepilot-ai-review -->`，后续同一 PR 再次审查时会更新已有评论，不会重复刷屏。

评论回写默认关闭。需要回写到 GitHub PR 时，设置 `CODEPILOT_GITHUB_COMMENT_ENABLED=true` 并配置 `CODEPILOT_GITHUB_TOKEN`。

## 项目亮点

- 把 GitHub PR 审查做成了完整工程闭环，而不是单点 LLM 调用。
- 通过 RabbitMQ 把耗时操作异步化，避免接口阻塞。
- 用 pgvector 支撑 RAG 编码规范库，适合后续持续扩展。
- 用 `@AiService` + `@Tool` 形成可解释、可扩展的审查能力。
- PR 评论支持幂等更新，适合真实协作场景。

## 后续规划

- 支持 inline review comment。
- 支持更丰富的规则库和分层规范。
- 支持更细粒度的 diff 位置定位。
- 支持审查历史对比和趋势分析。
- 支持更多语言和更多扫描工具。
