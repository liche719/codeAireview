# CodePilot 简历证据链与面试讲法

这份文档用于把 CodePilot 从“项目功能介绍”整理成“求职可讲项目”。目标是让简历、GitHub README 和面试回答能互相对上：简历写到的职责，在代码里能找到实现；面试说到的难点，有测试或运行验证支撑。

## 一句话定位

CodePilot 是一个面向 GitHub Pull Request 的 Java AI Review 系统。它不是把 diff 直接丢给大模型，而是把 PR 审查拆成 Webhook 触发、任务幂等、异步消费、上下文构建、规则召回、确定性检查、LLM 审查、结果校验、问题落库和 GitHub 评论回写的一条工程流水线。

## 项目背景

团队代码审查容易遇到三个问题：

1. 人工 Review 依赖经验，SQL 风险、敏感信息、测试缺失等问题容易漏。
2. 直接调用大模型审查 diff 容易产生空泛建议，评论位置也不一定能落到 PR 变更行。
3. PR 更新、重复 Webhook、评论命令复审等真实 GitHub 场景下，需要任务幂等、失败可追踪和评论可更新。

CodePilot 的设计目标是把 AI 审查做成可异步调度、可验证、可回写的后端系统，而不是一次性 prompt wrapper。

## 我的职责

- 负责审查任务主链路：统一手动接口、PR Webhook 和评论命令触发入口，创建 review task，并通过 RabbitMQ 异步执行耗时审查。
- 负责审查上下文和规划：根据 PR changed files 构建文件摘要、风险信号、相关代码片段和审查重点，避免所有文件使用同一份大 prompt。
- 负责规则与模型结果融合：在 LLM 前强制执行 SQL 风险、敏感信息、测试缺失等确定性规则，再和模型结果去重合并。
- 负责评论落地质量：用 patch verification 和 location guard 过滤无法绑定变更证据的问题，并支持 Summary Comment 幂等更新与 inline comment fallback。
- 负责运行和质量验证：补充离线评估、单元测试、运行态 smoke 测试和基础并发测试，沉淀项目可运行证据。

## 核心技术难点与解决方案

### 1. 多入口触发下的任务幂等

**问题**：同一个 PR 可能通过手动接口、Webhook `opened/synchronize/reopened`、评论区 `/review` 多次触发。如果每次都创建完整审查任务，会重复消耗模型调用和污染 PR 评论区。

**方案**：

- Webhook 层使用 Redis 短 TTL 去重，按 `owner/repo/pr/headSha` 或 comment id 构建去重键。
- 任务创建层按 `repoOwner + repoName + prNumber + headSha + commentMode` 复用 PENDING/RUNNING/SUCCESS 任务。
- 数据库迁移中为 head sha 和评论任务补充去重字段，避免并发插入绕过应用层判断。

**代码证据**：

- `src/main/java/com/codepilot/module/github/webhook/GitHubWebhookService.java`
- `src/main/java/com/codepilot/module/review/creator/ReviewTaskCreator.java`
- `src/main/resources/db/migration/V6__add_review_task_head_sha.sql`
- `src/main/resources/db/migration/V8__dedupe_pr_command_task_comments.sql`
- `src/main/resources/db/migration/V9__dedupe_review_task_head_sha.sql`

### 2. 审查流程异步化和失败可追踪

**问题**：拉取 PR 文件、构建上下文、调用模型、回写 GitHub 评论都是慢操作，如果放在接口线程里会导致请求阻塞；同时外部 GitHub、LLM、数据库和消息队列都有失败可能。

**方案**：

- 接口或 Webhook 只负责创建任务并投递消息，真正审查由 RabbitMQ consumer 执行。
- `ReviewTaskRunner` 统一推进任务状态：RUNNING -> SUCCESS/FAILED。
- 失败处理保留脱敏后的错误信息，并结合 RabbitMQ retry 判断是重试中还是最终失败，方便排查和补偿。

**代码证据**：

- `src/main/java/com/codepilot/task/ReviewTaskProducer.java`
- `src/main/java/com/codepilot/task/ReviewTaskConsumer.java`
- `src/main/java/com/codepilot/module/review/runner/ReviewTaskRunner.java`
- `src/main/java/com/codepilot/module/review/state/ReviewTaskStateManager.java`
- `src/main/java/com/codepilot/module/review/failure/ReviewTaskFailureHandler.java`

### 3. 降低 LLM 空泛输出

**问题**：如果只把 diff 拼成 prompt，模型可能输出和变更无关、无法定位到代码行、或者重复的建议。

**方案**：

- `ReviewFilePlanner` 先过滤 lock file、生成文件等低价值文件，并按文件预算控制审查范围。
- `SemanticReviewPlanner` 识别数据库、安全、配置、测试等风险面，为不同文件生成不同审查 focus。
- 确定性工具在 LLM 前执行，SQL 注入、硬编码密钥、测试缺失等高信号问题不依赖模型“主动想起”。
- `ReviewIssuePatchVerifier` 要求 LLM 问题尽量绑定 changed line、patch token 或审查计划风险面；`ReviewIssueLocationGuard` 再过滤无法评论到 diff 行的问题。

**代码证据**：

- `src/main/java/com/codepilot/module/review/planner/ReviewFilePlanner.java`
- `src/main/java/com/codepilot/module/review/planner/SemanticReviewPlanner.java`
- `src/main/java/com/codepilot/module/agent/review/DeterministicReviewToolRunner.java`
- `src/main/java/com/codepilot/module/review/processor/ReviewIssuePatchVerifier.java`
- `src/main/java/com/codepilot/module/review/processor/ReviewIssueLocationGuard.java`

### 4. GitHub 评论回写不污染 PR

**问题**：每次审查都新建一条 Summary Comment 会刷屏；inline comment 又可能因为行号不在 diff 中而失败。

**方案**：

- Summary Comment 使用 marker 查找并更新历史评论，避免重复创建。
- Inline comment 先经过 location guard 和 comment budget 控制。
- Inline 失败或没有成功发布时回退到 Summary Comment，保证审查结果仍能落到 PR 页面。

**代码证据**：

- `src/main/java/com/codepilot/module/review/publisher/ReviewCommentPublisher.java`
- `src/main/java/com/codepilot/module/review/service/impl/GitHubCommentServiceImpl.java`
- `src/main/java/com/codepilot/module/review/service/impl/GitHubInlineCommentServiceImpl.java`
- `src/main/java/com/codepilot/module/review/report/ReviewReportFormatter.java`

### 5. 可回归的审查质量验证

**问题**：AI Review 不适合只靠人工试用判断好坏；prompt、规则和 parser 改动都可能引入误报、漏报或解析失败。

**方案**：

- 构造离线 PR-like diff 样本，不调用真实 LLM，用 replay 输出跑生产审查路径。
- 统计 precision、recall、must-not-comment violation、parse failure rate 等指标。
- 覆盖 SQL 风险、硬编码密钥、prompt injection、缺少测试、API 契约变化、配置安全回归、非法 JSON 降级等场景。

**代码证据**：

- `src/test/java/com/codepilot/module/eval/AiReviewPipelineEvalTest.java`
- `src/test/java/com/codepilot/module/eval/DeterministicReviewEvalTest.java`
- `src/test/resources/eval/ai-review-pipeline-cases.json`
- `docs/ai-review-eval-baseline.md`

## 本轮架构重构证据

这一轮重构的目标不是堆功能，而是把“能跑的 AI Review demo”进一步整理成“能解释、能扩展、能承压、能失败恢复”的后端系统。重点改动如下：

- 文件级审查支持并发执行：`ReviewFileReviewer` 只保留编排职责，审查顺序由 `ReviewFilePrioritizer` 根据 semantic review plan 调整，具体执行由 `ReviewFileReviewExecutor` 使用 `reviewFileExecutor` 控制并发；单个文件失败时生成 `AI_REVIEW_FAILED` 系统问题，其他文件继续审查，全部失败才让任务失败。
- RabbitMQ 重试状态统一：新增 `RabbitRetryAttemptResolver` 和 `RetryAttempt`，让 review task 和 PR command task 共用同一套 attempt/maxAttempts/finalAttempt 判断，失败日志和任务状态更一致。
- GitHub Client 职责拆分：`GithubRequestExecutor` 负责 REST 执行、rate limit 判断、重试等待和脱敏异常；`GithubLinkedIssueResolver` 负责 GraphQL closing issue 和 PR body closing keyword 解析，`GithubClient` 回到 API 编排层。
- Patch validation 沙箱化：`JGitPatchExecutor` 把命令白名单、Docker sandbox 命令生成、环境变量清理、输出脱敏截断、验证执行拆成独立组件；构建类命令默认禁止，必须显式开启并使用 Docker 模式。
- Patch verification 证据模型拆分：`ReviewIssuePatchVerifier` 保留 LLM 问题过滤入口，`ReviewIssuePatchEvidence` 负责 diff token 和风险路径对齐，`ReviewIssuePlanEvidence` 负责审查计划证据，`ReviewIssueTextTokens` 统一 token 提取和规范化，降低“评论必须有证据”这条防线的复杂度。
- Semantic review planning 拆分：`ReviewPlanRiskCollector` 汇总风险面和 change type，`ReviewPlanPriorityFileScorer` 负责优先级评分，`ReviewPlanFileFocusBuilder`、`ReviewPlanCrossFileFocusBuilder`、`ReviewPlanVerificationHintBuilder` 和 `ReviewPlanQualityEstimator` 分别负责文件审查重点、跨文件关注点、验证提示和计划置信度，`SemanticReviewPlanner` 回到计划编排入口。
- Prompt formatter 拆分：`AiReviewPlanPromptFormatter`、`AiReviewGraphPromptFormatter`、`AiReviewRelatedContextFormatter` 分别负责计划、图谱和相关上下文，降低 prompt 组装类的复杂度。
- SQL 确定性规则拆分：`SqlRiskTool` 保留规则入口和日志，`SqlAstRiskAnalyzer` 负责 JSQLParser/SQL 候选提取，`SqlStringConcatenationDetector` 负责字符串拼接 SQL 识别，`SqlRiskIssueFactory` 统一生成问题描述；同时补充 `insert ... ${}` 的 MyBatis 风险回归测试。
- 跨文件图谱和相关源码上下文拆分：`RepositoryGraphSnapshotBuilder` 回到 DTO 适配入口，`RepositoryGraphSnapshotAssembler` 负责编排节点/边排序和 focus 生成，`RepositoryGraphNodeAccumulator` 负责图谱评分；`RepoSourceExcerptExtractor` 拆出候选收集、import 候选解析、source/test 配对解析、源码拉取截断和路径安全工具，支撑“相关文件召回”能力继续扩展。

**新增/更新测试证据**：

- `src/test/java/com/codepilot/common/retry/RabbitRetryAttemptResolverTest.java`
- `src/test/java/com/codepilot/module/command/git/PatchValidationRunnerTest.java`
- `src/test/java/com/codepilot/module/review/planner/ReviewPlanRiskCollectorTest.java`
- `src/test/java/com/codepilot/module/review/planner/ReviewPlanPriorityFileScorerTest.java`
- `src/test/java/com/codepilot/module/git/client/GithubClientTest.java`
- `src/test/java/com/codepilot/module/review/processor/ReviewFileReviewerTest.java`
- `src/test/java/com/codepilot/module/review/processor/ReviewIssuePatchVerifierTest.java`
- `src/test/java/com/codepilot/module/tool/impl/SqlRiskToolTest.java`
- `src/test/java/com/codepilot/module/review/graph/RepositoryGraphSnapshotTest.java`
- `src/test/java/com/codepilot/module/review/context/RepoSourceExcerptExtractorTest.java`
- `src/test/java/com/codepilot/module/agent/prompt/AiReviewContextFormatterGraphTest.java`
- `src/test/java/com/codepilot/module/review/context/ReviewContextBuilderTest.java`

## 已验证结果

- 全量自动化测试：503 个测试运行，0 failure，0 error，2 skipped。
- 本轮关键回归测试：`GithubClientTest`、`ReviewPlanRiskCollectorTest`、`PatchValidationRunnerTest`、`ReviewFileReviewerTest`、`RabbitRetryAttemptResolverTest` 共 26 个测试运行，0 failure，0 error。
- SQL 规则回归测试：`SqlRiskToolTest`、`DeterministicReviewEvalTest`、`AiReviewPipelineEvalTest`、`AiReviewServiceImplTest` 共 25 个测试运行，0 failure，0 error。
- 图谱和源码上下文回归测试：`RepositoryGraphSnapshotTest`、`AiReviewContextFormatterGraphTest`、`SemanticReviewPlannerTest`、`ReviewPlanPriorityFileScorerTest`、`RepoSourceExcerptExtractorTest`、`ReviewContextBuilderTest`、`ReviewTaskProcessorTest`、`AiReviewPipelineEvalTest` 共 19 个测试运行，0 failure，0 error。
- Patch verification 回归测试：`ReviewIssuePatchVerifierTest` 覆盖 changed line、patch text 丢弃、review plan 证据、patch risk area 兜底和 deterministic source，共 5 个测试运行，0 failure，0 error。
- 离线 AI Review 评估：6 个样本场景，Precision 85.71%，Recall 100%，must-not-comment violation rate 0%。
- 本地运行态 smoke：验证 OpenAPI、API Key 鉴权、Webhook HMAC 签名、unsupported event 忽略、PR opened 创建任务、Redis 去重、数据库落库、RabbitMQ 投递和 DLQ 链路。
- 本地并发验证：核心查询和 Webhook 触发接口在 20 并发下成功率 100%，P95 约 59ms 以内；PR 创建任务场景 20 次成功，P95 约 35ms。

说明：这些结果来自本地 Docker / mock LLM / 测试 GitHub 场景，适合证明核心链路可运行和可回归，不等同于生产容量上限。

## 简历可用表达

### 精简版

CodePilot AI 智能代码审查系统

面向 GitHub Pull Request 的 AI Review 后端系统，负责审查任务编排、异步执行、规则召回、模型审查、结果落库和 GitHub 评论回写。通过 Redis + headSha 去重避免重复审查，使用 RabbitMQ 解耦 Webhook 触发和耗时审查流程；在 LLM 前强制执行 SQL 风险、敏感信息、测试缺失等确定性规则，并通过 patch verification / location guard 过滤无法绑定变更证据的问题。完成离线评估与运行态验证，503 个自动化用例通过，离线评估 Precision 85.71%、Recall 100%。

### 简历项目条目版

- 设计 PR 审查任务主链路，统一手动接口、GitHub Webhook 和评论命令入口，通过 Redis 去重、headSha 任务复用和状态流转避免重复审查，支撑任务可查询、可重试、可追踪。
- 基于 RabbitMQ 拆分“快速接收触发”和“耗时审查执行”，消费者串联 changed files 拉取、审查规划、上下文构建、规则检测、LLM 审查、问题落库和评论发布，并在失败时记录脱敏错误与重试状态。
- 构建 planner + deterministic rules + RAG + LLM 的审查流程：按文件类型和风险面生成审查 focus，前置 SQL/Secret/Test 等确定性规则，再合并模型结果，减少纯 LLM 审查的空泛建议。
- 实现 patch verification、location guard 和评论 fallback：优先将问题绑定 changed line / patch token / review plan evidence，inline comment 失败时回退 Summary Comment，并通过 marker 幂等更新 PR 顶部评论。
- 建立离线评估和运行验证基线，覆盖 SQL 风险、硬编码密钥、prompt injection、缺少测试、API 契约变化和非法 JSON 降级等场景；503 个自动化用例通过，离线评估 Precision 85.71%、Recall 100%。

## 面试讲法

### 1. 项目整体怎么讲

我做的是一个面向 GitHub PR 的 AI 代码审查系统。核心思路不是 diff 直接调用大模型，而是先把 PR 转成可追踪的 review task，再通过 RabbitMQ 异步执行审查。审查过程中会拉取 changed files，按文件类型和风险面生成审查计划，再执行确定性规则和 RAG 规则召回，最后调用 LLM 输出结构化问题。模型结果不会直接发布，会经过 patch verification 和 location guard，只有能和变更行、patch token 或审查计划证据对上的问题才会落库和回写 GitHub。

### 2. 你在里面负责什么

我主要负责后端主链路：任务创建和幂等、异步执行、审查上下文构建、规则和模型结果合并、GitHub 评论回写，以及离线评估和运行态验证。这个项目里我比较关注两件事：第一是 AI 结果要能落到真实 PR 工作流，第二是系统要能解释为什么留下这条评论，而不是只生成一段文本。

### 3. 你遇到的一个真实问题

运行态测试时发现 pgvector 初始化存在风险：如果 `rule_chunk.embedding` 被建成无维度 `VECTOR`，后续创建 HNSW 索引会失败，因为 pgvector 要求 HNSW 索引列必须有固定维度，比如 `VECTOR(1536)`。这个问题说明只跑单测还不够，必须在全新数据库上做启动验证。当前仓库的初始建表已使用 `VECTOR(${embeddingDimension})`，并保留向量列规范化迁移和数据库迁移测试，降低新环境初始化失败的风险。

### 4. 为什么不用纯 LLM

纯 LLM 的问题是不可控：可能漏掉明显规则，也可能生成和 diff 无关的建议。所以我把审查拆成两部分：确定性规则负责 SQL 风险、Secret、测试缺失这类高信号问题；LLM 负责语义理解和综合建议。最后由 result merger 去重，再通过 patch verifier 过滤无证据问题。这样系统更像工程流水线，而不是聊天机器人。

### 5. 怎么证明不是 demo

有三类证据：第一，项目接入了 GitHub Webhook、API Key 鉴权、RabbitMQ、PostgreSQL/pgvector、Redis 和 GitHub 评论回写这些真实后端组件；第二，单测覆盖 503 个用例，包含 Webhook、任务状态、评论、规则、RAG、LLM parser、fix 命令等模块；第三，运行态 smoke 在 Docker 环境下验证了鉴权、Webhook 签名、任务创建、Redis 去重、数据库落库和 MQ/DLQ 链路。

## 下一轮项目补强建议

1. 增加一份可公开的 GitHub sandbox E2E 截图或录屏，展示 PR 触发、任务落库、Summary Comment 更新和 inline comment。
2. 把运行态 smoke 脚本整理进仓库，减少“测试报告在本地但仓库不可复现”的落差。
3. 扩充离线评估样本，把真实匿名 PR diff 加到 `ai-review-pipeline-cases.json`，提高评估可信度。
4. 补一个简单的任务指标接口或日志说明，如任务耗时、失败类型、发布成功率，方便面试时讲可观测性。
