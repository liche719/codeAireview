# CodePilot 代码地图

> 这份文档回答一个问题：**"这个功能到底在哪一行？"**
> 每一节都给出入口、调用顺序、涉及的类与配置项。所有条目都来自当前仓库代码，没有推测。
>
> 约定：`文件:行号` 指向当前 HEAD 的代码位置（行号会随改动漂移，类名和方法名不会）。

---

## 0. 快速上手

```powershell
copy .env.example .env                 # 1. 环境变量
docker compose up -d                   # 2. PostgreSQL:15432 / Redis:16379 / RabbitMQ:5672
mvn spring-boot:run                    # 3. 启动应用（Flyway 自动建表）
powershell -ExecutionPolicy Bypass -File scripts/smoke-local.ps1   # 4. 冒烟
mvn clean test                         # 5. 全量测试（540 个用例）
```

| 入口 | 方式 | 代码位置 |
|---|---|---|
| 手动创建审查 | `POST /api/reviews` | `ReviewController:34` |
| GitHub Webhook | `POST /api/github/webhook` | `GitHubWebhookController:18` |
| 查询任务/问题 | `GET /api/reviews/{id}` 等 5 个 | `ReviewController:39-65` |
| 规则库 | `/api/rules` | `RuleDocumentController` |
| GitHub 工具接口 | `POST /api/github/parse-pr`、`GET /api/github/pulls/files` | `GithubController:29,34` |

---

## 1. 数据模型（唯一事实来源 = Flyway）

数据库 schema **只有一处定义**：`src/main/resources/db/migration/V1__init.sql` ~ `V17__...sql`，应用启动时由 Flyway 自动执行（`application.yml:12-18`）。

> 历史上仓库根目录有一个 `schema.sql`，它只覆盖了 7 张表且已与迁移脱节，**已删除**。不要再以任何 `schema.sql` 为依据。

| 表 | 归属迁移 | 用途 |
|---|---|---|
| `review_task` | V1 / V6 / V9 / V12 | 一次 PR 审查任务；含 `execution_token`、`lease_until`、`attempt_count`、`publish_*` |
| `review_task_outbox` | V12 | 审查任务本地消息表（与 `review_task` 同事务写入） |
| `review_file` | V1 / V7 | 本次审查的 changed file |
| `review_issue` | V1 / V5 / V7 / V11 | 最终审查问题；含 `issue_type_zh`、`final_score`、`publish_decision` |
| `llm_call_log` | V1 / V7 | LLM 调用审计（含耗时） |
| `rule_document` / `rule_chunk` | V1 / V3 / V7 | 规则原文与向量切片（`embedding VECTOR(${embeddingDimension})`） |
| `pr_command_task` | V4 / V8 / V13 / V14 / V15 | PR 评论命令任务（CHAT / FIX_PREVIEW / FIX_PUBLISH / FIX_MERGE） |
| `pr_command_task_log` | V4 / V7 | 命令执行日志（含 Tool 调用轨迹） |
| `ai_review_cache` | V10 | LLM 审查结果缓存 |
| `pr_command_task_outbox` | V15 | 命令任务本地消息表（`UNIQUE (command_task_id)`） |
| `agent_run` / `agent_step` | V16 | 可恢复 Agent 运行与步骤 |
| `agent_web_resource` | V17 | Agent 本轮搜索返回的 URL 白名单（`UNIQUE (agent_run_id, url_hash)`） |

**并发正确性的关键索引**（读代码前先理解这几条，否则看不懂为什么不会重复执行）：

- `uq_review_task_active_head_mode`（V9）：`(repo_owner, repo_name, pr_number, head_sha, review_comment_mode) WHERE head_sha IS NOT NULL AND status IN ('PENDING','RUNNING','SUCCESS')`
- `uq_pr_command_task_fix_comment_id`（V8）：`WHERE command_type = 'FIX' AND comment_id IS NOT NULL`
- `uq_pr_command_task_chat_comment_id`（V13）：`WHERE command_type = 'CHAT'`
- `uq_pr_command_task_fix_publish_source` / `uq_pr_command_task_fix_merge_source`（V14）

---

## 2. 链路 A：工作流模式（触发 → 审查 → 回写）

### A1. 触发与入口幂等

```
GitHub Webhook ──► GitHubWebhookController:18
                       │
                       ▼
                  GitHubWebhookService.handle  (GitHubWebhookService.java:62)
                       ① webhookEnabled 开关           :63
                       ② HMAC-SHA256 验签             :68  → GitHubWebhookSignatureVerifier
                       ③ payload 解析                 :72  → GitHubWebhookPayloadParser
                       ④ 仓库 allowlist              :78  → GithubRepositoryPolicy
                       ⑤ bot 自评论过滤              :88  (避免自己回复自己)
                       ⑥ Redis 30s 去重              :99  (DEDUP_TTL :24)
                       ⑦ issue_comment → 命令路由   :111 → GithubCommandRouter（见链路 B）
                       ⑧ pull_request → 创建审查任务 :127 → ReviewTaskServiceImpl.createTask
```

去重 key 的构造规则在 `GitHubWebhookService.buildDedupKey:165`：

| 场景 | key |
|---|---|
| `issue_comment` 且已知 comment id | `codepilot:webhook:pr-command:{owner}:{repo}:{pr}:{commentId}` |
| `issue_comment` 无 comment id | `codepilot:webhook:delivery:{delivery}` |
| 其余事件（有 head sha） | `codepilot:webhook:pr-head:{owner}:{repo}:{pr}:{headSha}` |
| 兜底 | `codepilot:webhook:pr:{owner}:{repo}:{pr}` |

**Redis 挂掉不会阻断业务**：`isDuplicate` 捕获异常后返回 false 并继续创建任务（`:149-154`），把幂等责任交给数据库唯一索引。这是一个有意的取舍。

评论模式由事件类型决定（`resolveReviewCommentMode:209`）：`pull_request.opened` → `INLINE_ONLY`，其余 → `SUMMARY_ONLY`。

### A2. 事务内建任务 + 写本地消息

```
ReviewTaskServiceImpl.createTask(prUrl, title, mode, headSha)   @Transactional  :53-61
   ├─ ReviewTaskCreator.create(...)                              ReviewTaskCreator.java:31
   │     ├─ GithubPrUrlParser.parse(prUrl)
   │     ├─ GithubRepositoryPolicy.assertAllowed(owner, repo)
   │     ├─ findReusableTask(...)   ← 按 repo+pr+headSha+mode 复用 PENDING/RUNNING/SUCCESS  :104
   │     ├─ reviewTaskMapper.insert(task)
   │     └─ catch DuplicateKeyException → 再查一次（并发兜底）    :58-76
   └─ ReviewTaskMessageDispatcher.enqueue(taskId)  → review_task_outbox INSERT   :15-17
```

**关键点：任务行与 outbox 行在同一个 `@Transactional` 里提交。** 所以不存在"任务建了但消息丢了"。投递是事后由后台任务完成的。

> ⚠️ **复用只对 Webhook 生效。** `findReusableTask` 在 `headSha` 为空时直接返回 null（`ReviewTaskCreator:105`），而 `POST /api/reviews` 只传 `prUrl`（`ReviewController:36` → `ReviewTaskServiceImpl:36-38` 传 `null`），head sha 要到执行阶段才由 `ReviewTaskHeadShaRefresher` 补上。因此重复调用手动 API 会创建多个任务。

### A3. outbox → RabbitMQ（Publisher Confirm）

```
ReviewTaskOutboxPublisher               (定时任务，@Scheduled，默认每 1000ms)
   ├─ reviewTaskOutboxMapper 领取到期且未被锁定的记录
   ├─ ReviewTaskProducer.send(taskId) → exchange: codepilot.review.task.exchange
   │                                     queue:    codepilot.review.task.queue
   │                                     routing:  codepilot.review.task
   ├─ 等待 Publisher Confirm（默认 5s）  → 成功则标记 PUBLISHED
   └─ 失败/超时 → 指数退避重试（1s 起，上限 1m，最多 10 次）
```

- 队列/DLQ/exchange 常量：`task/ReviewTaskProducer.java:20-30`
- 声明与绑定：`common/config/RabbitMqConfig.java:24-65`
- 配置：`application.yml:160-168`（`CODEPILOT_REVIEW_OUTBOX_*`）
- Publisher Confirm 开关：`application.yml:31-32`（`publisher-confirm-type: correlated`、`publisher-returns: true`）

对应地，命令任务走一套完全对称的队列：`codepilot.pr.command.task.queue`（`task/PrCommandTaskProducer.java:20-30`），发布器是 `module/command/queue/PrCommandTaskOutboxPublisher`。

### A4. 消费与原子领取（租约）

```
ReviewTaskConsumer.consume                                     task/ReviewTaskConsumer.java:16
   └─ ReviewTaskServiceImpl.processTask(taskId)                :64
        ├─ getById(taskId)  → 不存在则抛 BusinessException
        ├─ ReviewTaskRunner.claim(taskId)                      ReviewTaskRunner.java:36
        │     └─ ReviewTaskStateManager.claim                  ReviewTaskStateManager.java:23
        │           └─ ReviewTaskMapper.claimForExecution      ReviewTaskMapper.java:16-36
        │                 UPDATE ... SET status='RUNNING', execution_token=?, lease_until=?,
        │                              attempt_count = attempt_count + 1
        │                 WHERE id=? AND (status='PENDING'
        │                      OR (status='RUNNING' AND lease_until < now))
        └─ claim 返回 null ⇒ 重复投递，记指标后直接 return    :69-75
```

**"租约"而不是"分布式锁"的全部含义就在这条 SQL 里**：
- 条件更新天然互斥，只有 1 个消费者能拿到 token；
- 没有额外组件参与，崩溃后靠 `lease_until < now` 让任务变回可领取；
- 后续**每一次状态写入都必须带 `execution_token`**，否则 `updated == 0` 抛"执行所有权丢失"（`ReviewTaskStateManager.assertExecutionOwned:73`）。

### A5. 单任务执行

```
ReviewTaskRunner.run(task)                                       :40-58
 ├─ ReviewTaskHeadShaRefresher.refresh(task)                    ReviewTaskHeadShaRefresher.java:19
 │     （此时才从 GitHub 拉真实 head sha 并落库）
 ├─ ReviewTaskProcessor.process(task)                           ReviewTaskProcessor.java:26-43
 │     ├─ GithubClient.listPullRequestFiles(owner, repo, pr)
 │     ├─ ReviewFilePlanner.plan(taskId, changedFiles)          （见 A6）
 │     ├─ ReviewFileReviewer.review(task, reviewFiles)          （见 A7）
 │     └─ ReviewTaskProcessingResult(totalFiles, totalIssues, riskLevel, files, issues)
 ├─ ReviewTaskResultStore.replaceAndComplete(task, result)      @Transactional  :25-45
 │     ├─ DELETE review_file / review_issue WHERE task_id=?
 │     ├─ saveBatch(reviewFiles) / saveBatch(reviewIssues)
 │     └─ ReviewTaskStateManager.markSuccess(...)  → status='SUCCESS', publish_status='PENDING'
 ├─ ReviewMetrics.recordTaskExecution(...)  "success"
 ├─ catch Exception → ReviewTaskFailureHandler.handle(...) → 抛 IllegalStateException 触发 MQ 重试
 └─ ReviewTaskPublicationService.publish(taskId)   ← 成功后才会走到这里
```

### A6. 文件规划：谁进审查、谁被跳过

`ReviewFilePlanner.plan:30` → `applyReviewLimits:57`，两轮筛选：

**第一轮（硬性跳过）**，`toReviewFile:42` + `getBasicSkipReason:127`：
- patch 为空/null（二进制或超大文件）
- 文件路径为空
- 生成物/锁文件：`.lock`、`package-lock.json`、`yarn.lock`、`.min.js`、`dist/`、`target/`、`build/`（`shouldSkipPath:192`）
- 单文件 patch 超过 `max-patch-chars-per-file`（默认 12000）

**第二轮（预算选择）**，`reviewPriority:156` 打分后从高到低贪心选，受 `max-files-per-task`（默认 30）和 `max-total-patch-chars`（默认 80000）约束，未入选者标记 skip 原因。

打分权重（**这是面试可讲的设计**）：

| 信号 | 分值 |
|---|---|
| 安全敏感路径，或 patch 含 `password`/`secret`/`token`/`auth`/`permission` | +1000 |
| 数据库路径，或 patch 含 `select `/`update `/`delete `/`insert `/`alter table` | +900 |
| 公共 API 路径 | +800 |
| 配置路径 | +700 |
| 依赖清单 | +650 |
| 生产代码 | +600 |
| 测试代码 | +350 |
| 文档 | **−250** |

### A7. 上下文构建 + 文件级并发

```
ReviewFileReviewer.review(task, reviewFiles)                    ReviewFileReviewer.java:76-105
 ├─ ReviewContextBuilder.build(task, reviewFiles)     ← 注意：整任务只构建一次，所有文件共享
 ├─ 过滤 skipped
 ├─ ReviewFilePrioritizer.prioritize(files, context)  按语义计划的 priority score 重排  :19
 ├─ ReviewFileReviewExecutor.reviewFiles(taskId, files, context)     （并发，见下）
 └─ ReviewFindingRanker.rank(issues)
 └─ 若"可审查文件全部失败" → 抛异常，整个任务失败                     :99-103
```

`ReviewFileReviewExecutor.reviewFiles:62-83`：

```java
int parallelism = min(max(1, maxParallelFiles), max(1, fileCount));
if (parallelism <= 1 || fileCount <= 1) → 串行 stream
else → CompletableFuture.supplyAsync(..., reviewFileExecutor) 后 join
```

- 线程池：`ReviewFileExecutorConfig` → bean 名 `reviewFileExecutor`
- 单文件异常被 `reviewFileSafely:85-101` 吞掉并转成"系统 issue"（`ReviewFileFailureIssueFactory`），所以**一个文件失败不会拖垮整个 PR**；只有全失败才抛。
- 指标：`ReviewMetrics.recordModelFileReview(success)`，即 `codepilot.review.model.file`

每个文件内部（`ReviewSingleFileReviewer.review:27-54`）：

```
AiReviewService.reviewFile(request)                       （见 A8）
 ├─ ReviewIssueAssembler.toReviewIssues(...)
 ├─ ReviewIssuePatchVerifier.keepVerified(...)            ← 质量门 1：证据校验
 └─ ReviewIssueLocationGuard.keepOnlyCommentableChangedLines(...)  ← 质量门 2：行可评论
```

### A8. 单文件审查编排（确定性规则优先 + 降级）

`AiReviewServiceImpl.reviewFile`（`AiReviewServiceImpl.java:37-88`）是这个系统最核心的编排，**按顺序**：

| 步骤 | 行为 | 行号 |
|---|---|---|
| 1 | patch 为空 → 直接返回空结果 | :41-44 |
| 2 | **先跑确定性规则** `DeterministicReviewToolRunner.run(...)` | :47-52 |
| 3 | `ReviewLlmGate.isAvailable(filePath, 确定性 issue 数)` 为 false → **只返回确定性结果** | :53-55 |
| 4 | 单文件 patch 超 LLM 预算 → 跳过 LLM，保留确定性结果并追加说明文案 | :56-63 |
| 5 | LLM 抛异常：**若确定性规则已发现问题 → 降级返回确定性结果**；否则向上抛（该文件失败） | :70-83 |
| 6 | LLM 返回 null → 只返回确定性结果 | :84-86 |
| 7 | 正常 → `ReviewResultMerger.merge(llmResult, deterministicResult)` | :87 |

> 这里体现的设计意图：**确定性规则是"下限保障"，LLM 是"增量"，任何 LLM 故障都不能让已经确定的规则发现丢失。**

### A9. 结果落库与风险等级

- 原子替换：`ReviewTaskResultStore.replaceAndComplete`（`@Transactional(rollbackFor = Exception.class)`，`:25`）
- 风险等级：`ReviewIssueAssembler.calculateRiskLevel(issues)`
- 失败处理：`ReviewTaskFailureHandler.handle`（脱敏错误信息 + 结合 RabbitMQ retry attempt 判断是否终态）

### A10. 评论回写与幂等

```
ReviewTaskPublicationService.publish(taskId)                    :28-70
 ├─ ReviewTaskMapper.claimPublication(...)     ← 条件更新抢发布权（含 PUBLISHING 陈旧恢复）:134-152
 ├─ ReviewCommentPublisher.publish(task)                        ReviewCommentPublisher.java:23
 │    ├─ INLINE_ONLY  → GitHubInlineCommentService.commentInlineIssues(taskId)
 │    │                  有成功 → published
 │    │                  无成功或抛异常 → **回退到 Summary**      :39-42
 │    └─ SUMMARY_ONLY → GitHubCommentService.commentReviewResult(taskId)
 └─ finishPublication → PUBLISHED / SKIPPED / FAILED(带 nextAttemptAt) / FAILED_FINAL
```

幂等三件套：

| 机制 | 实现 | 作用 |
|---|---|---|
| **marker** | `codepilot.github.comment-marker`（`application.yml:117`） | Summary Comment 找到历史评论并**更新**而不是新建，PR 不被刷屏 |
| **fingerprint** | `GitHubInlineCommentFingerprintBuilder` / `...Extractor` | inline 评论按问题指纹去重 |
| **发布预算** | `inline-comment-max-per-task`（默认 10）+ `ReviewCommentBudgetAllocator` | 限制单任务 inline 评论数量 |

发布失败**不会**把任务改成 FAILED：审查状态与发布状态是两套独立字段（`status` vs `publish_status`）。重试由 `ReviewTaskPublicationJob`（默认 5000ms 轮询）驱动，达到 `publication-max-attempts`（默认 5）后进入 `FAILED_FINAL`。

---

## 3. 链路 B：Agent 模式

### B1. 入口与任务创建

```
issue_comment webhook
 └─ GitHubWebhookService:111 → GithubCommandRouter.route        GithubCommandRouter.java:38
      ├─ isAllowedCommentAuthor：comment author association ∈ {OWNER, MEMBER, COLLABORATOR}（可配）:74
      ├─ repo allowlist 再校验                                    :42-46
      └─ 按 GithubCommandType 分发到 GithubCommandHandler
           ├─ CHAT → ChatCommandHandler.handle                    ChatCommandHandler.java:26
           │     └─ PrCommandTaskService.createChatTask(payload)  @Transactional
           ├─ FIX  → FixCommandHandler.handle                     FixCommandHandler.java:29
           └─ REVIEW → ReviewCommandHandler
```

**路由不再做意图预分类**：所有 `@x-pilotx ...` 都是 `CHAT`，由模型根据完整自然语言自行选工具。
（旧的 LLM 意图分类器 `GithubCommandIntentAiAssistant` 及其 prompt/测试已在本次重构中删除。）

### B2. 命令任务执行

```
PrCommandTaskConsumer.consume                                  task/PrCommandTaskConsumer.java:16
 └─ PrCommandTaskServiceImpl.processCommandTask(id)            :101-138
      ├─ PrCommandTaskStateManager.claim(id)   ← 与 review_task 同款的 token + 租约
      └─ 按 command_type 分发：
           CHAT        → AgentChatTaskRunner.run(task)          :112-117
           FIX_PREVIEW → PrCommandTaskRunner.run(task)          :119-121
           FIX_PUBLISH → FixPublishTaskRunner.run(task)         :123-128
           FIX_MERGE   → FixMergeTaskRunner.run(task)           :130-135
           其他        → markFailed("Unsupported command type") :137
```

### B3. Agent 运行时与工具

- Runner：`module/command/runner/AgentChatTaskRunner.java`
- 运行/步骤持久化：`AgentRunService`（`agent_run` / `agent_step`），限制来自 `AgentRuntimeProperties`：
  `max-model-turns=8`、`max-tool-calls=12`、`max-continuations=4`、`run-deadline=30m`、`continuation-context-max-chars=4000`
- 工具集：`module/command/tool/GithubCommandChatTool.java`

| 类型 | 工具 | 说明 |
|---|---|---|
| 只读 | PR 元信息、变更文件摘要、已存审查证据、团队规则检索 | 作用于当前持久化任务绑定的 repo/PR |
| 只读（可选） | `searchWeb`、`fetchWebDocument` | 默认关闭；SearXNG，见链路 G |
| 动作 | `startReviewWorkflow`、`startFixPreview` | 只能启动受控异步 Workflow |
| 动作 | `approveFixPreview`、`approveFixMerge` | 只入队审批任务，服务端二次校验 |
| **不存在** | Git / Shell / 文件写入 / GitHub merge | Agent 拿不到这些能力 |

### B4. 挂起与恢复

```
Agent 调 startReviewWorkflow
  → 子任务（review_task）异步入队
  → 当前 agent_run 置 WAITING_WORKFLOW
  → 子任务完成后 AgentWorkflowResumeJob 扫描 findWaiting(waiting-poll-batch-size=50)
  → 把结构化结果按 continuation-context-max-chars 截断后回注
  → 恢复原 run 继续推理（受 max-continuations 限制）
```

### B5. Tool 轨迹

`AgentToolTraceAspect`（AOP）+ `AgentTaskTraceContext` 把当前 command task id 绑定到工具调用线程，所有 Tool 调用写入 `pr_command_task_log`，可完整回放一次运行。

> 注意：`pom.xml` 为此引入了 `spring-boot-starter-aop`。

### B6. 已知的测试专用残留（了解即可）

`PrCommandTaskServiceImpl` 有 3 个构造器，其中 3 参/6 参版本把 `reliableClaimEnabled` 置为 `false`（`:67,80`）。Spring 使用带 `@Autowired` 的 7 参构造器，因此生产环境恒为 `true`（走 `commandTaskStateManager.claim`）；另两个构造器只服务于早期单元测试。属于可接受的测试接缝，但读代码时要知道它们不代表生产行为。

---

## 4. 链路 C：RAG（pgvector）

```
规则文档写入：POST /api/rules                RuleDocumentController
规则索引：    POST /api/rules/{id}/index     RuleIndexService
   └─ RuleTextSplitter 切片 → EmbeddingService 向量化 → rule_chunk
检索：        POST /api/rules/search         RuleSearchService
   └─ 向量化 query → pgvector 相似检索（PgVectorUtils）→ topK
审查时召回：  ReviewRagService（AiReviewServiceImpl 调用处）
   └─ ReviewRagServiceImpl
        ├─ ReviewRagQueryBuilder      从 filePath + patch 构造检索 query
        ├─ ReviewRagCacheKeyBuilder   构造缓存 key
        ├─ ReviewRagCache             TTL + LRU（cache-max-size=256, cache-ttl-seconds=300）
        └─ ReviewRagRuleSelector      选出注入 prompt 的规则片段
```

- 向量维度：`EmbeddingDimensionResolver` + `EmbeddingDimensionBootstrap` + `EmbeddingFlywayConfiguration`（V1 里用 `${embeddingDimension}` 占位符，启动时确定为真实维度）
- 注入上限：`max-review-rules-chars`（默认 4000）
- 配置：`application.yml:189-204`

---

## 5. 链路 D：确定性规则

接口：`module/tool/rule/DeterministicReviewRule`；统一执行器：`module/agent/review/DeterministicReviewToolRunner`。

| 规则 | 实现 | 要点 |
|---|---|---|
| SQL 风险 | `SqlRiskTool` + `SqlAstRiskAnalyzer` + `SqlStringConcatenationDetector` + `SqlRiskIssueFactory` | 用 **jsqlparser 做 AST 分析**，不是纯正则；能识别字符串拼接 SQL、缺 WHERE 的 UPDATE/DELETE 等 |
| 敏感信息 | `SecretScanTool` | 扫描 patch 中的硬编码密钥 |
| 测试缺失 | `TestSuggestionTool` | 业务代码变更但无对应测试时提示 |

开关：`application.yml:205-209`（`codepilot.tools.*`，默认开启）。
工具在 LLM **之前**执行（`AiReviewServiceImpl:47`），结果与 LLM 结果由 `ReviewResultMerger` 合并去重（`ReviewIssueDeduplicator`）。

---

## 6. 链路 E：可靠性与恢复总表

| 机制 | 类 | 配置 | 触发方式 |
|---|---|---|---|
| 入口去重 | `GitHubWebhookService` + Redis | 固定 30s TTL | 每次 webhook |
| 任务复用 | `ReviewTaskCreator.findReusableTask` | DB 唯一索引 V9 | 创建任务时 |
| 事务型 outbox | `ReviewTaskOutboxPublisher` | `outbox-batch-size=20`、`outbox-lock-timeout=2m`、`outbox-max-attempts=10` | 定时轮询 1000ms |
| Outbox 发布确认 | `RabbitTemplate` | `publisher-confirm-type=correlated`、`outbox-confirm-timeout=5s` | 每次投递 |
| 消费端原子抢占 | `ReviewTaskMapper.claimForExecution` | — | 每次消费 |
| 执行租约恢复 | `ReviewTaskExecutionRecovery` | `execution-lease=15m`、`execution-recovery-interval-millis=30000` | 定时扫描 |
| MQ 重试 | Spring AMQP retry | `max-attempts=3`、1s→2s→max 5s | 消费抛异常 |
| 死信 | `RabbitMqConfig` + DLQ | `default-requeue-rejected=false` | retry 耗尽 |
| 发布重试 | `ReviewTaskPublicationService` + `ReviewTaskPublicationJob` | `publication-max-attempts=5`、轮询 5000ms | 定时扫描 |
| 命令 outbox | `PrCommandTaskOutboxPublisher` | `CODEPILOT_COMMAND_OUTBOX_*` | 定时轮询 |
| 命令租约恢复 | `PrCommandTaskRecoveryJob` | `command.execution-lease=15m`、`recovery-batch-size=50` | 定时扫描 |
| Agent 挂起恢复 | `AgentWorkflowResumeJob` | `waiting-poll-batch-size=50` | 定时扫描 |

后台任务总开关：`CODEPILOT_SCHEDULING_ENABLED`（`application.yml:93-94`）。

---

## 7. 链路 F：安全边界

| 边界 | 实现 | 默认 |
|---|---|---|
| REST API Key | `ApiKeyAuthFilter`（`/api/**`） | `enabled=true`，Key 为空则 401 |
| 限流 | `ApiRateLimitFilter` → `RedisFixedWindowRateLimiter` | 60 次/60s；**Redis 不可用时降级到 `FixedWindowRateLimiter` 单实例内存限流**（`:58-62`） |
| Webhook 验签 | `GitHubWebhookSignatureVerifier` | 必开；`skip-signature-when-secret-empty` 默认 `false` |
| 仓库白名单 | `GithubRepositoryPolicy` | 空 = 不限制（生产建议必填） |
| 评论者权限 | `GithubCommandRouter.isAllowedCommentAuthor` | 仅 `OWNER,MEMBER,COLLABORATOR` |
| GitHub 鉴权 | `GithubAuthTokenProvider`（PAT / App JWT → installation token） | `auth-mode=auto` |
| 日志脱敏 | `SensitiveDataSanitizer`（另有 `PromptInputSanitizer`、`MarkdownSanitizer`） | 全局 |
| fix 能力开关 | `fix-enabled` / `fix-publish-enabled` / `fix-merge-enabled` | **三者均 false** |
| 命令白名单 | `ValidationCommandPolicy` | 仅 `git diff --check`，精确匹配 argv，不经 shell |
| Docker 沙箱 | `DockerSandboxCommandFactory` | 构建类命令必须 `execution-mode=docker`；默认 `network=none`、不继承环境变量 |
| 路径逃逸防护 | `ApplyPatchTargetGuard` | patch 只能落在允许路径 |
| 外部检索 | `SearxngSearchService` / `WebDocumentFetchService` / `AgentWebResourceService` | 默认关闭；HTTPS + 域名白名单 + 拒绝私网 DNS/重定向/非文本/超大响应 |

---

## 8. 链路 G：Fix 两阶段审批

```
① Agent 调 startFixPreview → dry-run 修复任务（永不 push）
   └─ 产物用 FixPatchArtifactCodec 编码，记录 patch SHA-256 与允许路径（V14）

② 用户精确评论「批准发布修复 <previewTaskId>」（或 approve fix preview <id>）
   └─ FixApprovalCommandParser → FixApprovalService
        校验：精确命令文本 · 同一请求者 · repo/PR · source head SHA · patch SHA-256
             · 允许路径 · 服务端开关
   └─ 通过后入队 FIX_PUBLISH → FixPublishTaskRunner
        重新应用并校验 patch → 推送随机 codepilot/fix-* 分支 → 创建以原 PR head 为 base 的修复 PR
        幂等：唯一索引 + 远端分支/PR 查询

③ 用户再次精确评论「批准合并修复 <publishTaskId>」（或 approve merge fix <id>）
   └─ 校验：分支 · commit SHA · PR 非 draft · head/base 一致 · mergeable_state=clean
   └─ FIX_MERGE → FixMergeTaskRunner → 用 expected SHA 调 GitHub merge API
```

**发布授权 ≠ 合并授权**，两次审批相互独立，都由服务端读持久化的原始评论校验——模型自己调 Tool 不构成授权。

---

## 9. 质量门（LLM 输出 → 可发布问题）

| 门 | 类 | 作用 |
|---|---|---|
| 1. 证据校验 | `ReviewIssuePatchVerifier`（+`ReviewIssuePatchEvidence`、`ReviewIssuePlanEvidence`、`ReviewIssueTextTokens`） | 问题必须能绑定 changed line / patch token / review plan risk，否则丢弃 |
| 2. 行可评论 | `ReviewIssueLocationGuard`（+`DiffLineMapper`） | 判断能否落到 GitHub 可评论的 diff 行；不能则只能进 Summary |
| 3. 评分与预算 | `ReviewFindingRanker`（+`ReviewFindingScoreCalculator`、`ReviewFindingDuplicateResolver`、`ReviewFindingPublishSorter`、`ReviewCommentBudgetAllocator`） | 打分、去重、发布排序、预算分配 |

---

## 10. 关键配置速查

| 配置 | 默认 | 位置 |
|---|---|---|
| `codepilot.review.max-files-per-task` | 30 | `application.yml:151` |
| `codepilot.review.max-parallel-files` | 2 | `:155` |
| `codepilot.review.max-patch-chars-per-file` | 12000 | `:153` |
| `codepilot.review.max-total-patch-chars` | 80000 | `:154` |
| `codepilot.review.execution-lease` | 15m | `:159` |
| `codepilot.review.publication-max-attempts` | 5 | `:166` |
| `codepilot.llm.max-review-patch-chars` | 12000 | `:178` |
| `codepilot.rag.top-k` | 3 | `:199` |
| `codepilot.agent-runtime.*` | 见 `:83-92` | — |
| `spring.rabbitmq.listener.simple.*` | concurrency 2 / max 4 / prefetch 1 / retry 3 | `:33-45` |

环境变量清单：`.env.example`（137 项）+ 逐项中文说明 `docs/env.md`。

---

## 11. 观测指标（Actuator `/actuator/metrics`）

| 指标 | 类型 | 含义 |
|---|---|---|
| `codepilot.review.task.creation` | Counter | 新建 vs 复用 |
| `codepilot.review.task.queue.wait` | Timer | 创建 → 被领取的排队时间 |
| `codepilot.review.task.duration` | Timer | 任务耗时（success/retry/failed） |
| `codepilot.review.task.execution` | Counter | 执行结果 + `duplicate_skipped` |
| `codepilot.review.model.file` | Counter | 文件级模型审查成功/失败 |
| `codepilot.review.outbox.publish` | Counter | outbox 发布成功/重试/最终失败 |
| `codepilot.review.github.publish` | Counter | 评论成功/跳过/重试/最终失败 |

> ⚠️ 项目**没有引入 Prometheus registry**，所以这些指标只能在 `/actuator/metrics` 单点查询，不能直接出 p95 趋势图或报表。任何"p95 延迟"的说法都不来自本项目。

---

## 12. 已知限制与注意事项（诚实清单）

1. **手动 API 不去重复用**：`POST /api/reviews` 不带 head sha，重复调用会创建多个任务。Webhook 路径不受影响。见 A2。
2. **测试数口径**：`540 tests, 0 failures, 0 errors`；启动 Docker 时 2 skipped，未启动时 6 skipped（4 个 Testcontainers 用例整类跳过 + 1 个符号链接用例 + 1 个 opt-in GitHub E2E）。skipped 全部是条件依赖。
3. **离线评估样本很小**：`AiReviewPipelineEvalTest` 要求 ≥6 个 PR-like 场景（当前 6 个），Precision `85.71%`（= 6/7，在 **issueType 级别按 case 拼 key** 计算）、Recall `100%`。指标口径见 `docs/ai-review-eval-baseline.md` 的 Non-Goals。
4. **没有生产环境数据**：无 QPS、无真实 PR 量、无真实 token 成本。`docs/resume-project-evidence.md` 已声明这些结果来自本地 Docker / mock LLM / 测试 GitHub 场景。
5. **`PrCommandTaskServiceImpl` 的 `reliableClaimEnabled`** 是测试接缝，生产恒为 true。见 B6。
6. **`docs/archive/` 下的调研报告是历史快照**，其中的数字（350 tests、85 个测试文件、commit `cb9d440`）已全部过时，不要引用。
