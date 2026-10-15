# 架构说明

CodePilot AI 的核心不是把 diff 直接丢给大模型，而是把 PR 审查拆成一条后端流水线：触发、去重、排队、拉取上下文、规划审查重点、并发审查文件、合并结果、证据校验、落库、回写 GitHub。

## 总览

```mermaid
flowchart TD
    A["入口层<br/>REST API / GitHub Webhook / PR Comment"] --> B["任务层<br/>ReviewTaskCreator / PrCommandTaskCreator"]
    B --> C["消息层<br/>RabbitMQ queues + DLQ"]
    C --> D["执行层<br/>ReviewTaskRunner / PrCommandTaskRunner"]
    D --> E["GitHub 集成<br/>changed files / comments / linked issues"]
    E --> F["规划层<br/>ReviewFilePlanner / SemanticReviewPlanner"]
    F --> G["上下文层<br/>ReviewContextBuilder / repo graph / source excerpts"]
    G --> H["审查层<br/>Deterministic tools + RAG + LLM"]
    H --> I["质量门<br/>merge / rank / patch verification / location guard"]
    I --> J["持久化<br/>review_task / review_file / review_issue / rule_chunk"]
    J --> K["发布层<br/>Summary Comment / Inline Comment"]
```

## 模块边界

### 入口层

- `ReviewController` 提供手动创建和查询审查任务的 REST API。
- `GitHubWebhookController` 接收 GitHub Webhook。
- `GitHubWebhookService` 负责开关判断、HMAC 验签、payload 解析、仓库 allowlist、Redis 去重和命令路由。

### 任务层

- `ReviewTaskCreator` 把 PR URL、title、head sha 和评论模式转成 `review_task`。
- `ReviewTaskServiceImpl` 提供创建、查询和处理任务的统一入口。
- `PrCommandTaskCreator` 把所有 `@x-pilotx ...` 自然语言评论转成 `CHAT` Agent 任务；只有无 mention 的兼容命令 `/review` 直接创建 Review Workflow。

### 消息层

- `ReviewTaskProducer` / `ReviewTaskConsumer` 使用 RabbitMQ 投递和消费审查任务。
- `PrCommandTaskProducer` / `PrCommandTaskConsumer` 使用独立队列处理 PR 命令任务。
- `RabbitMqConfig` 为两类任务都配置 durable queue、direct exchange 和 dead-letter queue。
- Spring AMQP listener 并发由 `CODEPILOT_RABBITMQ_LISTENER_CONCURRENCY`、`CODEPILOT_RABBITMQ_LISTENER_MAX_CONCURRENCY`、`CODEPILOT_RABBITMQ_LISTENER_PREFETCH` 控制。

### 执行层

- `ReviewTaskRunner` 统一推动任务状态：RUNNING -> SUCCESS/FAILED。
- `ReviewTaskProcessor` 负责拉取 changed files、规划文件、清理旧 issue、执行审查并保存结果。
- `ReviewTaskFailureHandler` 统一处理失败、脱敏错误信息，并结合 RabbitMQ retry attempt 判断最终失败。

### 规划和上下文层

- `ReviewFilePlanner` 按文件大小、patch 预算、路径风险和文件类型决定哪些文件进入审查。
- `SemanticReviewPlanner` 汇总风险面，生成 priority files、file focus、cross-file focus 和 verification hints。
- `ReviewContextBuilder` 组合 file summary、语义信号、关联 patch、PR linked issues、仓库图谱和相关源代码片段。
- `RepositoryGraphSnapshotBuilder` 和 `RepoSourceExcerptExtractor` 支持跨文件上下文召回。

### 审查层

- `DeterministicReviewToolRunner` 调用确定性规则工具。
- `SqlRiskTool`、`SecretScanTool`、`TestSuggestionTool` 覆盖 SQL、敏感信息和测试缺失风险。
- `ReviewRagServiceImpl` 召回规则文档，并由 `ReviewRagCache` 做 TTL + LRU 缓存。
- `ReviewPromptBuilder` 和 `AiReviewContextFormatter` 组装受约束的审查 prompt。
- `LangChain4jReviewLlmClient` 调用 OpenAI-compatible 模型。

### 质量门

- `ReviewResultMerger` 合并 deterministic tool 和 LLM 输出。
- `ReviewIssuePatchVerifier` 要求 LLM issue 尽量绑定 changed line、patch token、review plan evidence 或高信号风险面。
- `ReviewIssueLocationGuard` 判断 issue 是否能落到可评论 diff 行。
- `ReviewFindingRanker` 负责评分、去重、预算和发布顺序。

### 发布层

- `ReviewCommentPublisher` 统一发布 GitHub 评论。
- `GitHubCommentServiceImpl` 使用 marker 幂等创建/更新 Summary Comment。
- `GitHubInlineCommentServiceImpl` 使用 issue fingerprint 避免重复 inline comment。
- inline 发布失败或不可定位时，系统仍可通过 Summary Comment 保留审查结果。

### Agent Chat 层

- `ChatCommandHandler` 只负责校验评论入口、创建 `CHAT` 类型的 `pr_command_task`；任务先写入 command outbox，再由后台发布器可靠投递 RabbitMQ。
- `AgentChatTaskRunner` 在消费者线程中加载最新 review session context，调用带 Tool 的 LangChain4j Assistant，并将最终回复发布到 GitHub。
- `AgentTaskTraceContext` 将当前 command task id 绑定到 Tool 调用线程；`GithubCommandChatTool` 记录 `TOOL_CALL`，任务日志同时记录上下文加载、模型回复和评论发布结果。
- Agent 自主组合 PR 查询、变更摘要、已存 finding、团队规则检索和可选 SearXNG 外部搜索等只读 Tool，也可调用 `startReviewWorkflow` 或 `startFixPreview` 启动受控异步 Workflow。
- `startFixPreview` 从当前持久化 Agent 任务派生仓库和 PR 身份，只允许 `dryRun=true`；Agent 不持有原始 Git/Shell Tool。
- `approveFixPreview` / `approveFixMerge` 只是受控 Workflow 的入口，不是 GitHub 写操作。`FixApprovalService` 从持久化的原评论验证精确命令、同一请求者、仓库/PR、source head SHA、patch SHA-256、允许路径和服务端开关，模型自行调用 Tool 不构成授权。
- `FIX_PUBLISH` 重新应用并校验已批准 patch，推送持久化的随机 `codepilot/fix-*` 分支，再创建以原 PR head 分支为 base 的修复 PR；唯一索引和远程分支/PR 查询保证重试幂等。
- `FIX_MERGE` 使用第二次独立审批，校验修复 PR 非 draft、head/base/commit 与发布任务一致且 `mergeable_state=clean`，再用 expected SHA 调用 GitHub merge API。发布审批不等于合并审批。
- `pr_command_task_outbox` 为 CHAT/FIX/审批动作提供事务内可靠投递；消费者通过 execution token 和 lease 原子领取，租约过期后由 recovery job 恢复，避免重复消息并发执行或 broker 短暂不可用丢任务。
- `AgentRunService` 将一次 Agent 运行拆成 `agent_run` 与 `agent_step`，限制模型轮次、Tool 调用数、continuation 次数和总截止时间；异步 Workflow 会把运行置为 `WAITING_WORKFLOW`，完成后恢复为待执行。
- 跨评论上下文只加载同一 repo/PR 的有限成功 CHAT 任务，并作为不可信数据注入；不参与审批或 Tool 作用域决策。
- `fetchWebDocument` 只允许当前 Agent run 最近一次 `searchWeb` 返回的 HTTPS URL，且要求显式域名白名单并执行私网 DNS、响应大小、内容类型和重定向防护。

## 典型执行链路

```mermaid
sequenceDiagram
    participant GitHub
    participant Webhook as GitHubWebhookService
    participant Task as ReviewTaskService
    participant MQ as RabbitMQ
    participant Runner as ReviewTaskRunner
    participant Review as Review Pipeline
    participant DB as PostgreSQL
    participant Comment as GitHub Comment Publisher

    GitHub->>Webhook: pull_request opened/synchronize/reopened
    Webhook->>Webhook: HMAC verify + Redis dedupe + allowlist
    Webhook->>Task: create review task
    Task->>DB: persist review_task
    Task->>MQ: publish task id
    MQ->>Runner: consume task
    Runner->>Task: mark RUNNING
    Runner->>Review: fetch diff + plan + review
    Review->>DB: persist review_file/review_issue
    Runner->>Task: mark SUCCESS or FAILED
    Runner->>Comment: publish summary/inline comments
    Comment->>GitHub: create/update PR comments

    GitHub->>Webhook: issue_comment @x-pilotx natural-language request
    Webhook->>Task: create CHAT command task
    Task->>MQ: publish command task
    MQ->>Agent: AgentChatTaskRunner
    Agent->>Tool: compose query/RAG/search/workflow Tools
    Tool-->>Agent: bounded untrusted result
    Agent->>DB: persist task logs and response preview
    Agent->>GitHub: publish chat response

    GitHub->>Webhook: exact publish approval comment
    Webhook->>Task: create CHAT approval task
    Agent->>Tool: approveFixPreview(previewTaskId)
    Tool->>DB: validate persisted comment + patch identity
    Tool->>MQ: enqueue FIX_PUBLISH
    MQ->>GitHub: push bot branch + create fix PR

    GitHub->>Webhook: separate exact merge approval comment
    Agent->>Tool: approveFixMerge(publishTaskId)
    Tool->>DB: validate persisted comment + published PR identity
    Tool->>MQ: enqueue FIX_MERGE
    MQ->>GitHub: merge with expected head SHA
```

## 并发模型

项目有两层并发：

1. 任务级并发：RabbitMQ listener 可以并发消费多个 review task 或 PR command task。
2. 文件级并发：单个 review task 内，`ReviewFileReviewExecutor` 使用 `CompletableFuture` 和 `reviewFileExecutor` 并发审查多个文件。

关键配置：

```env
CODEPILOT_RABBITMQ_LISTENER_CONCURRENCY=2
CODEPILOT_RABBITMQ_LISTENER_MAX_CONCURRENCY=4
CODEPILOT_RABBITMQ_LISTENER_PREFETCH=1
CODEPILOT_REVIEW_MAX_PARALLEL_FILES=2
CODEPILOT_REVIEW_MAX_FILES_PER_TASK=30
```

这样做的目的不是无脑拉高吞吐，而是在 LLM、GitHub API、数据库和队列之间留出可控背压：任务间通过 RabbitMQ 排队，任务内通过文件级并发缩短大 PR 的审查时间。

并发入口可以用 `scripts/concurrency-smoke.ps1` 演示：脚本并发发送同一个 PR/headSha 的 webhook，正常结果是 1 个请求创建或复用审查任务，其余请求被 Redis 去重识别为 `duplicate event`。

## 可靠性设计

- Webhook 层使用 Redis 30 秒 TTL 去重，避免 GitHub 重试或重复 delivery 造成重复审查。
- 任务创建层按 repo/pr/headSha/commentMode 复用已有任务，降低重复 LLM 调用成本。
- 创建审查任务时在同一数据库事务写入 `review_task_outbox`，后台发布器通过 RabbitMQ Publisher Confirm 确认投递；失败按指数退避重试，发布锁超时后可再次领取。
- 消费者通过条件更新原子领取任务并写入执行令牌和租约，重复消息只能有一个执行者；实例宕机后，过期租约会恢复为待执行并重新投递。
- RabbitMQ retry + DLQ 保存最终消费失败消息，便于排查。
- 文件与问题先在内存完成规划，成功后在短事务内原子替换旧结果并将任务置为成功，避免失败重试留下半套结果。
- GitHub 评论发布使用独立状态机和有限重试，发布失败不会把已经完成的审查改成失败。
- GitHub 评论发布中的 `PUBLISHING` 状态带超时恢复，进程中断后可以重新领取；达到最大尝试次数后进入终态，避免无限重试。
- 单文件审查失败不会直接拖垮整次 PR 审查。
- GitHub Summary Comment 使用 marker 幂等更新，避免 PR Conversation 被重复刷屏。
- GitHub inline comment 使用 fingerprint 去重。
- LLM cache 和 RAG cache 降低重复审查成本。

## 安全边界

- REST API 默认 API Key 保护。
- REST API 默认固定窗口限流。
- GitHub Webhook 必须 HMAC 验签。
- GitHub 仓库 allowlist 控制可处理范围。
- GitHub App 模式使用短期 installation token，生产优先于 PAT。
- fix 模式默认关闭。
- fix validation 默认只允许 `git diff --check`，不通过 shell 执行命令。
- 构建类命令必须显式开启 Docker sandbox，并且默认无网络、不继承应用敏感环境变量。
- 日志和错误信息会经过敏感信息脱敏。

## 数据模型

核心表：

- `review_task`：一次 PR 审查任务，包含 repo、prNumber、headSha、状态、风险等级和统计信息。
- `review_task_outbox`：审查任务的本地可靠消息，记录发布状态、尝试次数、下次重试时间和发布锁。
- `review_file`：一次审查中的 changed file 记录。
- `review_issue`：最终审查问题，包含 severity、issueType、filePath、lineNumber、source、ruleCode、evidence 等。
- `rule_document`：规则文档原文。
- `rule_chunk`：规则文档切片和 pgvector embedding。
- `pr_command_task`：PR 评论命令任务。
- `pr_command_task_log`：命令执行日志。
- `ai_review_cache`：LLM 审查缓存。
- `llm_call_log`：LLM 调用审计日志。

数据库迁移位于 `src/main/resources/db/migration/`，由 Flyway 在启动时执行。

## 质量验证

自动化测试覆盖：

- Webhook 验签、payload 解析、事件忽略和去重。
- RabbitMQ 配置、retry attempt 和 DLQ。
- API Key 与限流。
- GitHub Client、GitHub App 鉴权、评论回写和 inline comment 去重。
- RAG 检索、缓存、规则切片和 pgvector 工具。
- SQL/Secret/Test deterministic rules。
- Prompt、parser、LLM schema、降级路径。
- patch verification、location guard、finding ranking。
- 自动修复 patch 应用、命令白名单和 sandbox 约束。

当前全量测试基线为 540 个测试，0 failures/0 errors。Testcontainers 已使用隔离的 PostgreSQL/pgvector、Redis 和 RabbitMQ 验证命令 outbox、命令任务并发领取、租约恢复和 PR command publisher confirm。

Actuator `metrics` 端点提供以下低基数业务指标：

- `codepilot.review.task.creation`：新建与复用任务数。
- `codepilot.review.task.queue.wait`：任务从创建到消费者领取的排队时间。
- `codepilot.review.task.duration`：成功、重试和最终失败任务耗时。
- `codepilot.review.task.execution`：执行结果与重复投递跳过次数。
- `codepilot.review.model.file`：文件级模型审查成功数和失败数，可计算模型失败率。
- `codepilot.review.outbox.publish`：本地消息发布成功、重试和最终失败数。
- `codepilot.review.github.publish`：GitHub 评论成功、跳过、重试和最终失败数。
