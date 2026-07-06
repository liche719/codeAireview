# CodePilot 简历证据链与面试讲法

这份文档用于把 CodePilot 从“项目功能介绍”整理成“求职可讲项目”。目标是让简历、GitHub README 和面试回答能互相对上：简历写到的职责，在代码里能找到实现；面试讲到的难点，有测试、脚本或运行证据支撑。

## 一句话定位

CodePilot 是一个面向 GitHub Pull Request 的 Java AI Review 后端系统。它不是把 diff 直接丢给大模型，而是把 PR 审查拆成 Webhook 触发、任务幂等、RabbitMQ 异步消费、文件级并发审查、上下文构建、规则召回、确定性检查、LLM 审查、结果校验、问题落库和 GitHub 评论回写的一条工程流水线。

## 项目背景

团队代码审查容易遇到三个问题：

1. 人工 Review 依赖经验，SQL 风险、敏感信息、测试缺失等问题容易漏。
2. 直接用 LLM 审查 diff 容易输出空泛建议，评论也不一定能落到 PR 变更行。
3. 真实 GitHub 场景里，PR 更新、重复 Webhook、评论命令复审都需要幂等、失败可追踪和评论可更新。

CodePilot 的设计目标是把 AI 审查做成可调度、可验证、可回写的后端系统，而不是一次性 prompt wrapper。

## 我的职责

- 负责审查任务主链路：统一手动 API、PR Webhook 和评论命令入口，创建 review task，并通过 RabbitMQ 异步执行耗时审查。
- 负责审查上下文和规划：根据 PR changed files 构建文件摘要、风险信号、相关代码片段和审查重点，避免所有文件使用同一份大 prompt。
- 负责规则与模型结果融合：在 LLM 前强制执行 SQL 风险、敏感信息、测试缺失等确定性规则，再和模型结果去重合并。
- 负责评论落地质量：用 patch verification 和 location guard 过滤无法绑定变更证据的问题，并支持 Summary Comment 幂等更新与 inline comment fallback。
- 负责运行和质量验证：补充离线评估、单元测试、运行态 smoke 脚本和基础并发验证，沉淀项目可运行证据。

## 核心技术难点

### 1. 多入口触发下的任务幂等

问题：同一个 PR 可能通过手动接口、Webhook、`/review` 评论多次触发。如果每次都创建完整审查，会重复消耗模型调用并污染 PR 评论区。

方案：

- Webhook 层使用 Redis 短 TTL 去重，按 `owner/repo/pr/headSha` 或 comment id 构建去重 key。
- 任务创建层按 `repoOwner + repoName + prNumber + headSha + commentMode` 复用 PENDING/RUNNING/SUCCESS 任务。
- 数据库迁移补充 head sha 和评论任务去重字段，避免并发插入绕过应用层判断。

代码证据：

- `src/main/java/com/codepilot/module/github/webhook/GitHubWebhookService.java`
- `src/main/java/com/codepilot/module/review/creator/ReviewTaskCreator.java`
- `src/main/resources/db/migration/V6__add_review_task_head_sha.sql`
- `src/main/resources/db/migration/V8__dedupe_pr_command_task_comments.sql`
- `src/main/resources/db/migration/V9__dedupe_review_task_head_sha.sql`

### 2. 异步任务和文件级并发

问题：拉取 PR 文件、构建上下文、调用模型、回写 GitHub 都是慢操作，如果放在接口线程里会阻塞；大 PR 内多个文件串行审查也会拖慢反馈。

方案：

- 接口/Webhook 只创建任务并投递 RabbitMQ。
- `ReviewTaskRunner` 统一推进任务状态和失败处理。
- RabbitMQ listener 通过配置控制任务级并发。
- `ReviewFileReviewExecutor` 使用 `CompletableFuture` 和 `reviewFileExecutor` 控制单任务内文件级并发。
- 单文件失败生成系统 issue，其他文件继续审查。

代码证据：

- `src/main/java/com/codepilot/task/ReviewTaskProducer.java`
- `src/main/java/com/codepilot/task/ReviewTaskConsumer.java`
- `src/main/java/com/codepilot/module/review/runner/ReviewTaskRunner.java`
- `src/main/java/com/codepilot/module/review/processor/ReviewFileReviewExecutor.java`
- `src/main/java/com/codepilot/module/review/config/ReviewFileExecutorConfig.java`

### 3. 降低 LLM 空泛输出

问题：纯 LLM 审查可能漏掉显式规则，也可能生成和 diff 无关的建议。

方案：

- `ReviewFilePlanner` 过滤低价值文件并控制 patch 预算。
- `SemanticReviewPlanner` 识别 database/security/config/test 等风险面，生成文件级 focus。
- `DeterministicReviewToolRunner` 在 LLM 前执行 SQL、Secret、Test 确定性规则。
- `ReviewIssuePatchVerifier` 校验 issue 是否能绑定 changed line、patch token、review plan risk 或高信号风险面。
- `ReviewIssueLocationGuard` 再判断是否能落到 GitHub 可评论 diff 行。

代码证据：

- `src/main/java/com/codepilot/module/review/planner/ReviewFilePlanner.java`
- `src/main/java/com/codepilot/module/review/planner/SemanticReviewPlanner.java`
- `src/main/java/com/codepilot/module/agent/review/DeterministicReviewToolRunner.java`
- `src/main/java/com/codepilot/module/review/processor/ReviewIssuePatchVerifier.java`
- `src/main/java/com/codepilot/module/review/processor/ReviewIssueLocationGuard.java`

### 4. GitHub 评论不刷屏

问题：每次审查都新建 Summary Comment 会刷屏；inline comment 又可能因为行号不在 diff 中而失败。

方案：

- Summary Comment 使用 marker 查找并更新历史评论。
- Inline Comment 发布前经过 location guard 和 comment budget 控制。
- Inline comment 使用 fingerprint 去重。
- Inline 发布失败或不可定位时回退 Summary Comment。

代码证据：

- `src/main/java/com/codepilot/module/review/publisher/ReviewCommentPublisher.java`
- `src/main/java/com/codepilot/module/review/service/impl/GitHubCommentServiceImpl.java`
- `src/main/java/com/codepilot/module/review/service/impl/GitHubInlineCommentServiceImpl.java`
- `src/main/java/com/codepilot/module/review/report/ReviewReportFormatter.java`

### 5. 自动修复的安全边界

问题：`@x-pilotx fix` 会应用补丁并可能推送提交，如果没有边界，风险比普通审查高很多。

方案：

- fix 模式默认关闭。
- 只复用当前 head sha 的成功审查结果。
- 限制 patch 文件数和变更行数。
- 校验命令白名单精确匹配，不通过 shell 执行。
- 默认只允许 `git diff --check`。
- Maven/Gradle/npm 等构建类命令必须显式开启 Docker sandbox，默认不继承敏感环境变量，默认无网络。
- `ApplyPatchTargetGuard` 保护 patch 目标路径，避免路径逃逸。

代码证据：

- `src/main/java/com/codepilot/module/command/git/JGitPatchExecutor.java`
- `src/main/java/com/codepilot/module/command/git/PatchValidationRunner.java`
- `src/main/java/com/codepilot/module/command/git/ValidationCommandPolicy.java`
- `src/main/java/com/codepilot/module/command/git/ApplyPatchTargetGuard.java`
- `src/main/java/com/codepilot/module/command/policy/FixPullRequestWritePolicy.java`

## 重构证据

本轮重构目标不是堆功能，而是把“能跑的 AI Review demo”整理成“能解释、能扩展、能承压、能失败恢复”的后端系统。

已完成的关键拆分：

- GitHub Client：拆出 `GithubRequestExecutor` 和 `GithubLinkedIssueResolver`。
- GitHub App 鉴权：拆出 `GithubAuthTokenProvider`、`GithubAppJwtFactory`、`GithubAppPrivateKeyParser`。
- Patch validation：拆出命令白名单、Docker sandbox、环境变量清理、输出脱敏截断。
- Apply patch：拆出 parser、section applier、hunk applier、target guard。
- Patch verification：拆出 patch evidence、plan evidence、text token 提取。
- Inline comment：拆出 body builder、fingerprint builder、fingerprint extractor。
- Semantic review planning：拆出 risk collector、priority scorer、file focus、cross-file focus、verification hint、quality estimator。
- Prompt formatter：拆出 plan、graph、related context formatter。
- RAG：拆出 query builder、cache key builder、TTL/LRU cache、rule selector。
- SQL 规则：拆出 AST analyzer、字符串拼接检测、issue factory。
- Review finding ranking：拆出 score calculator、duplicate resolver、publish sorter。
- 跨文件图谱和源码上下文：拆出 graph assembler、node accumulator、source excerpt extractor 和路径工具。

## 验证结果

- 全量自动化测试：`509 tests, 0 failures, 0 errors, 2 skipped`。
- 离线 AI Review 评估：6 个 PR-like 场景，Precision `85.71%`，Recall `100%`，must-not-comment violation rate `0%`。
- 评估覆盖：SQL 风险、硬编码密钥、prompt injection、缺少测试、API 契约变化、配置安全回归、非法 JSON 降级。
- 本地 smoke：覆盖 OpenAPI、API Key 鉴权、规则文档创建/查询、限流 header。
- GitHub 相关测试：覆盖 GitHub App 鉴权、PAT fallback、rate-limit retry、Summary Comment marker、inline fingerprint 去重。

说明：这些结果来自本地 Docker / mock LLM / 测试 GitHub 场景，适合证明核心链路可运行和可回归，不等同于生产容量上限。

## 简历可用表达

### 精简版

CodePilot AI 智能代码审查系统

面向 GitHub Pull Request 的 AI Review 后端系统，负责审查任务编排、异步执行、规则召回、模型审查、结果落库和 GitHub 评论回写。通过 Redis + headSha 去重避免重复审查，使用 RabbitMQ 解耦 Webhook 触发和耗时审查流程；在 LLM 前强制执行 SQL 风险、敏感信息、测试缺失等确定性规则，并通过 patch verification / location guard 过滤无法绑定变更证据的问题。完成 509 个自动化测试，离线评估 Precision 85.71%、Recall 100%。

### 条目版

- 设计 PR 审查任务主链路，统一手动 API、GitHub Webhook 和评论命令入口，通过 Redis 去重、headSha 任务复用和状态流转避免重复审查。
- 基于 RabbitMQ 拆分触发与审查执行，消费端串联 changed files 拉取、审查规划、上下文构建、规则检测、LLM 审查、问题落库和评论发布。
- 构建 planner + deterministic rules + RAG + LLM 审查流程，按文件类型和风险面生成审查 focus，前置 SQL/Secret/Test 等确定性规则，减少纯 LLM 审查的空泛建议。
- 实现 patch verification、location guard 和评论 fallback，优先将问题绑定 changed line / patch token / review plan evidence，并通过 marker 幂等更新 PR Summary Comment。
- 建立离线评估和运行验证基线，覆盖 SQL 风险、硬编码密钥、prompt injection、缺少测试、API 契约变化、非法 JSON 降级等场景；509 个自动化用例通过。

## 面试讲法

### 1. 项目整体怎么讲

我做的是一个面向 GitHub PR 的 AI 代码审查系统。核心思路不是 diff 直接调用大模型，而是先把 PR 转成可追踪的 review task，再通过 RabbitMQ 异步执行审查。审查过程中会拉取 changed files，按文件类型和风险面生成审查计划，再执行确定性规则和 RAG 规则召回，最后调用 LLM 输出结构化问题。模型结果不会直接发布，会经过 patch verification 和 location guard，只有能和变更行、patch token 或审查计划证据对上的问题才会落库和回写 GitHub。

### 2. 你负责什么

我主要负责后端主链路：任务创建和幂等、异步执行、审查上下文构建、规则和模型结果合并、GitHub 评论回写，以及离线评估和运行态验证。我比较关注两件事：第一，AI 结果要能落到真实 PR 工作流；第二，系统要能解释为什么留下这条评论，而不是只生成一段文本。

### 3. 为什么要做并发

并发有两层：任务级并发和文件级并发。任务级并发靠 RabbitMQ listener 配置，可以同时处理多个 PR；文件级并发靠 `ReviewFileReviewExecutor`，单个 PR 内多个文件可以并发审查。这样大 PR 的反馈会更快，同时又能通过 `maxParallelFiles`、`prefetch` 和队列积压控制背压，避免 LLM 和 GitHub API 被打爆。

### 4. 为什么不纯用 LLM

纯 LLM 的问题是不可控：可能漏掉显式规则，也可能输出和 diff 无关的建议。所以我把审查拆成两部分：确定性规则负责 SQL 风险、Secret、测试缺失这类高信号问题；LLM 负责语义理解和综合建议。最终由 result merger 去重，再通过 patch verifier 过滤无证据问题。这样系统更像工程流水线，而不是聊天机器人。

### 5. 怎么证明不是 demo

有三类证据：第一，项目接入了 GitHub Webhook、API Key 鉴权、RabbitMQ、PostgreSQL/pgvector、Redis 和 GitHub 评论回写这些真实后端组件；第二，自动化测试有 509 个，覆盖 Webhook、任务状态、评论、规则、RAG、LLM parser、fix 命令等模块；第三，有离线评估和 smoke 脚本，可以在不依赖真实 LLM 的情况下回归审查质量和基础运行链路。

## 后续可增强点

1. 增加公开可复现的 GitHub sandbox 截图或录屏，展示 PR 触发、任务落库、Summary Comment 更新和 inline comment。
2. 扩充离线评估样本，把真实匿名 PR diff 加到 `ai-review-pipeline-cases.json`。
3. 增加任务指标接口或 dashboard，展示任务耗时、失败类型、发布成功率和队列积压。
4. 将 GitHub App installation 管理扩展成多租户模型，支持 SaaS 化部署。
