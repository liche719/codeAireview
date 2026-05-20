# CodePilot AI

CodePilot AI 是一个面向 GitHub Pull Request 的 Java 后端 AI Review 服务，结合 Spring Boot、LangChain4j、RAG、Webhook、RabbitMQ 和 GitHub API，自动完成 PR 审查、问题归纳和评论回写。

## 项目背景

这个项目不是简单的“接一个大模型接口”，而是把 PR 审查做成一条完整工程链路：

1. 用户提交 PR 链接，或者 GitHub Webhook 自动触发；
2. 系统创建审查任务并异步入队；
3. 拉取 PR changed files 和 diff；
4. 结合编码规范库做 RAG 检索；
5. 结合 `@Tool` 做 SQL 风险、敏感信息、单测建议等确定性检测；
6. 输出结构化 JSON 审查结果；
7. 将 review issue 入库；
8. 生成 inline comment 和 Markdown Summary Comment 并回写到 GitHub PR。

它的目标是服务 Java 后端工程场景，而不是做一个只会聊天的模型壳子。

## 核心功能

- 手动 `POST /api/reviews` 创建 PR 审查任务。
- GitHub Webhook 自动触发 `opened`、`synchronize`、`reopened` 的 PR 审查。
- PR 评论区输入 `/review` 可手动触发一次审查。
- RabbitMQ 异步消费审查任务，避免接口阻塞。
- GitHub API 拉取 PR changed files 和评论。
- PostgreSQL + pgvector 存储规则文档和向量块。
- RAG 编码规范库检索。
- LangChain4j `@AiService` 调用大模型。
- LangChain4j `@Tool` 自主调用 SQL 风险、敏感信息、单测建议检测工具。
- 审查问题入库到 `review_issue`。
- GitHub PR 顶部 Summary Comment 幂等回写。
- 可选 GitHub PR inline review comment，无法定位或调用失败时降级到顶部 Summary Comment。

## 技术栈

- Spring Boot 3.5.x
- Spring Web
- Spring Validation
- MyBatis Plus
- PostgreSQL
- pgvector
- Redis
- RabbitMQ
- LangChain4j
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
RAG 检索编码规范
        ↓
LangChain4j AI Review + @Tool 检测
        ↓
review_issue 入库
        ↓
Markdown 报告生成
        ↓
GitHub PR inline comment + 顶部 Summary Comment
```

## 核心流程

```text
1. Webhook opened / synchronize / reopened，或 PR 评论区 `/review`
2. 签名校验 + Redis 去重
3. 复用 createTask(prUrl, title)
4. 发送 taskId 到 RabbitMQ
5. 消费者拉取 PR diff
6. RAG 召回规则上下文
7. @Tool 自主执行 SQL / SECURITY / TEST 检测
8. LLM 输出 JSON 审查结果
9. 保存 review_issue
10. 尝试对新增行创建 inline review comment
11. 生成或更新 GitHub PR 顶部 Summary Comment
```

## 本地启动

### 1. 准备环境变量

复制模板：

```powershell
copy .env.example .env
```

编辑 `.env`，至少填写：

- `CODEPILOT_GITHUB_TOKEN`
- `CODEPILOT_LLM_API_KEY`
- `CODEPILOT_EMBEDDING_API_KEY`
- `CODEPILOT_GITHUB_WEBHOOK_SECRET`

### 2. 启动本地依赖和应用

```powershell
powershell -ExecutionPolicy Bypass -File scripts/start-local.ps1
```

脚本会：

1. 检查 `.env` 是否存在；
2. 加载 `.env` 到当前进程；
3. 启动 Docker 依赖；
4. 执行 `mvn -DskipTests package`；
5. 启动应用 jar。

### 3. 常用地址

- OpenAPI: `http://localhost:8080/doc.html`
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`

## 环境变量说明

完整说明见 [docs/env.md](docs/env.md)。

几个最关键的变量：

- `CODEPILOT_GITHUB_TOKEN`：GitHub API Token，用于拉取 PR、创建评论和更新评论。
- `CODEPILOT_GITHUB_COMMENT_ENABLED`：是否启用 GitHub PR 顶部 Summary 评论回写。
- `CODEPILOT_GITHUB_INLINE_COMMENT_ENABLED`：是否启用 GitHub PR inline review comment，默认关闭。
- `CODEPILOT_GITHUB_INLINE_COMMENT_MAX_PER_TASK`：单个任务最多创建多少条 inline comment。
- `CODEPILOT_GITHUB_WEBHOOK_ENABLED`：是否启用 GitHub Webhook 接口。
- `CODEPILOT_GITHUB_WEBHOOK_SECRET`：Webhook 签名密钥。
- `CODEPILOT_LLM_API_KEY`：大模型 API Key。
- `CODEPILOT_EMBEDDING_API_KEY`：向量模型 API Key。
- `CODEPILOT_DB_URL`：PostgreSQL JDBC 地址。
- `CODEPILOT_RABBITMQ_USERNAME` / `CODEPILOT_RABBITMQ_PASSWORD`：RabbitMQ 凭证。

Fine-grained GitHub Token 建议权限：`Contents: Read`、`Pull requests: Read and write`、`Issues: Read and write`、`Metadata: Read`。开启 inline comment 时需要 Pull requests 写权限。

## API 使用示例

### 创建审查任务

```bash
curl -X POST http://localhost:8080/api/reviews ^
  -H "Content-Type: application/json" ^
  -d "{\"prUrl\":\"https://github.com/owner/repo/pull/123\"}"
```

### 查询任务详情

```bash
curl http://localhost:8080/api/reviews/123
```

### 查询审查问题

```bash
curl http://localhost:8080/api/reviews/123/issues
```

### 创建规则文档

```bash
curl -X POST http://localhost:8080/api/rules ^
  -H "Content-Type: application/json" ^
  -d "{\"title\":\"SQL 规范\",\"content\":\"...\"}"
```

### GitHub Webhook

```bash
curl -X POST http://localhost:8080/api/github/webhook ^
  -H "X-GitHub-Event: pull_request" ^
  -H "X-GitHub-Delivery: demo-1" ^
  -H "X-Hub-Signature-256: sha256=..." ^
  -H "Content-Type: application/json" ^
  -d "{...}"
```

## GitHub Webhook 配置

在 GitHub 仓库的 `Settings -> Webhooks` 中新增 Webhook：

- Payload URL: `https://your-domain/api/github/webhook`
- Content type: `application/json`
- Secret: 与 `CODEPILOT_GITHUB_WEBHOOK_SECRET` 保持一致
- Events: `Pull requests`、`Issue comments`

支持的 PR 事件：

- `opened`
- `synchronize`
- `reopened`

`Pull requests` 用于 PR 打开、更新、重新打开时自动审查；`Issue comments` 用于在 PR Conversation 中输入 `/review` 手动触发审查。普通 issue 评论、非 `/review` 内容和非 `created` 评论事件会被忽略。

## GitHub 命令代理

PR 评论支持以下命令：

```text
/review
@x-pilotx review
@x-pilotx fix dry-run
@x-pilotx fix
```

`@x-pilotx review` 会复用普通审查流水线，并创建新的 PR 总结评论。`@x-pilotx fix dry-run` 会生成并校验一个小型 unified diff，但不会推送提交。`@x-pilotx fix` 会使用最近一次成功的审查结果，在临时检出目录中应用补丁，运行校验命令，通过后才向当前 PR 分支推送新提交。

Fix 模式默认已开启。若要关闭，请设置 `CODEPILOT_GITHUB_FIX_ENABLED=false`。它只会写回同仓库的 PR 分支，token 仍需要 `Contents: Read and write`、`Pull requests: Read and write`、`Issues: Read and write` 和 `Metadata: Read`。

## RAG 规范库使用说明

1. 通过 `POST /api/rules` 创建规则文档。
2. 通过 `POST /api/rules/{id}/index` 将规则切片并向量化。
3. 审查时系统会按 PR diff 和文件路径检索相关规则片段。
4. 检索到的规则上下文会注入 AI Review Prompt。

这让模型在审查时能够参考团队编码规范，而不是只靠泛化能力猜测。

## PR 评论回写说明

审查成功后，系统会先尝试把可定位到新增行的问题写成 GitHub inline review comment，然后生成 Markdown 审查报告并回写到 GitHub PR 顶部评论。

当前策略是：

- 顶部 Summary Comment 使用 `<!-- codepilot-ai-review:liche719/codeAireview -->` marker 识别，存在则更新，不存在则创建；
- inline comment 默认关闭，需要设置 `CODEPILOT_GITHUB_INLINE_COMMENT_ENABLED=true`；
- inline comment 第一版只评论 diff 新增行，使用 GitHub Pull Request Review Comments API；
- inline comment 失败、行号不在新增行或超过数量上限时，会跳过 inline，顶部 Summary Comment 仍会照常生成或更新；
- inline comment 第一版不做跨任务幂等，反复审查同一个 PR 时可能在同一行产生新的 inline comment。

## 项目亮点

- 把 PR 审查做成了完整工程闭环，而不是单次 LLM 调用。
- 使用 RabbitMQ 解耦审查耗时逻辑。
- 使用 pgvector 做规则库检索。
- 使用 LangChain4j `@AiService` + `@Tool` 形成可解释、可扩展的 Agent 审查能力。
- 支持 Webhook 自动触发，适合真实 GitHub 工作流。
- 支持 PR 评论区 `/review` 手动触发，适合需要临时复审的场景。
- 支持 GitHub 评论回写，审查结果能直接落到 PR 页面。
- 顶部 Summary Comment 幂等更新，inline comment 可按配置开启。

## 后续规划

- 支持更细粒度的 diff position 定位。
- 支持更丰富的规则类型和多语言规则库。
- 支持审查历史对比和趋势分析。
- 支持更完整的任务幂等和 head sha 级别去重。

## 验证

```bash
mvn test
mvn -DskipTests package
```

如果你只想先看效果，建议按下面顺序：

1. 启动 PostgreSQL、Redis、RabbitMQ；
2. 配置 `.env`；
3. 启动应用；
4. 创建规则；
5. 发起 PR 审查；
6. 在 PR 页面查看 CodePilot 评论。
