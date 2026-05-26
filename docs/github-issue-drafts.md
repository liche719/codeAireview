# GitHub Issue 草案（中文）

这份文档按“可直接发到 GitHub”的方式整理了当前最值得保留的 6 个 issue。

说明：

- 不是所有 issue 都应该立刻创建
- 当前最建议先开的，是前 3 个 `P0`
- 后 3 个更适合作为下一阶段增强或产品化占位

---

## Issue 1

### 建议标题

`feat(eval): 建立端到端 AI Review 标注评测集与回归评测框架`

### 建议正文

#### 背景

当前项目已经具备以下测试基础：

- deterministic review eval
- prompt regression
- parser / schema / cache / planner 等单元测试

但目前仍然缺少一套真正衡量 AI Review 质量的评测体系。  
也就是说，系统现在更容易证明“代码还能跑”，却很难证明“review 结果更准了”。

这会直接带来两个问题：

1. 后续继续做 planner、ranking、repo graph 时，无法量化收益
2. 项目很容易陷入“功能越来越多，但不知道质量到底有没有提升”的状态

#### 现状证据

- 已有 deterministic eval：
  - `src/test/java/com/codepilot/module/eval/DeterministicReviewEvalTest.java`
  - `src/test/resources/eval/deterministic-review-cases.json`
- 已有 prompt regression：
  - `src/test/java/com/codepilot/module/eval/PromptRegressionEvalTest.java`
- 但尚未看到：
  - 面向真实 AI Review finding 的标注数据集
  - 离线 replay runner
  - precision / recall / false positive / must-not-comment 级别的系统评估报告

#### 目标

建立一套可回归、可量化、可比较不同 prompt / planner / model 方案的 AI Review benchmark。

#### 范围

- 建立 labeled PR diff case 数据集
- 为每个 case 标注：
  - expected findings
  - must-not-comment
  - allowed misses
  - scenario tags
- 建立离线 replay runner
- 输出评估报告
- 支持比较不同 prompt signature / planner version / model

#### 验收标准

- 至少覆盖这些 case：
  - SQL 风险
  - 安全敏感变更
  - 缺少测试
  - API 契约破坏
  - 配置回归
  - 多文件重构
- 至少输出这些指标：
  - precision
  - recall
  - must-not-comment violation rate
  - parse failure rate
  - average latency
  - token / cost
- 能比较同一组 case 在不同版本下的结果变化
- 至少产出一份 baseline 报告，作为后续重构对照基线

#### 非目标

- 不要求一开始就接入真实线上 GitHub PR
- 不要求第一阶段就建设超大评测平台

#### 价值

这是后续所有 AI 能力升级的评估地基。  
没有这项，项目对“更智能了”的叙述不够硬。

---

## Issue 2

### 建议标题

`feat(review): 建立 finding 排序、抑制与评论预算控制机制`

### 建议正文

#### 背景

当前系统已经具备：

- `ReviewPlan.confidence`
- `priorityFiles`
- `Patch Verification`
- issue merge / location guard / evidence 展示

但 review 结果层仍然缺少完整的“少而准”收敛机制。

这意味着随着系统能力增强，最容易出现的新问题不是“找不到问题”，而是：

- 评论太多
- 优先级混乱
- 低价值 finding 混入高价值 finding
- reviewer 开始忽略机器人输出

#### 现状证据

- `ReviewFileReviewer` 只会按 `ReviewPlan.priorityFiles` 调整文件审查顺序，不是 finding ranking
  - `src/main/java/com/codepilot/module/review/processor/ReviewFileReviewer.java`
- `GitHubInlineCommentServiceImpl` 有固定优先级排序和 `inline-comment-max-per-task`，但不是统一 ranking / suppression pipeline
  - `src/main/java/com/codepilot/module/review/service/impl/GitHubInlineCommentServiceImpl.java`
- `ReviewIssueAssembler` 只做基础过滤，不做最终 publish decision
  - `src/main/java/com/codepilot/module/review/assembler/ReviewIssueAssembler.java`
- `ReviewIssuePatchVerifier` 会做 grounding，但不会统一决定“这条 finding 值不值得发”
  - `src/main/java/com/codepilot/module/review/processor/ReviewIssuePatchVerifier.java`

#### 目标

把 review 输出从“能产出 finding”升级为“能稳定产出少而准、优先级清晰、噪声可控的 finding set”。

#### 范围

- 建立统一 finding score 模型
- 引入 suppression 机制
- 引入 comment budget allocator
- 统一 inline / summary publish decision
- 给 suppressed finding 记录 suppression reason

#### 建议设计

- 新增 `ReviewFindingRanker`
- 新增 `ReviewCommentBudgetAllocator`
- score 输入建议包括：
  - severity
  - confidence
  - source strength（TOOL / LLM）
  - patch grounding strength
  - risk area
  - review plan focus match
  - linked issue relevance
  - duplicate likelihood
- 输出建议包括：
  - finalScore
  - publishDecision
  - suppressionReason
  - commentChannelPreference

#### 验收标准

- 每次 review 都能得到排序后的 finding 列表
- 每条 finding 都有 publish / suppress 决策
- 支持配置：
  - 最大 inline comment 数
  - 最大 summary finding 数
- 大 PR 场景下不会出现评论泛滥
- 排序和抑制逻辑有可回归测试

#### 非目标

- 不做复杂机器学习排序器
- 不依赖用户反馈系统才能先跑起来

#### 价值

这是系统从“能输出评论”走向“长期可用”的关键一步。  
很多 reviewer 产品不是死在能力不够，而是死在噪声治理失败。

---

## Issue 3

### 建议标题

`feat(context): 构建 repository graph 与 symbol-aware 上下文检索能力`

### 建议正文

#### 背景

当前项目已经不再是简单 prompt wrapper，它已经具备：

- `SemanticReviewPlanner`
- relationship hints
- repo source excerpts
- patch verification

但底层上下文能力依然主要围绕 PR patch 工作。  
系统还没有真正的 repository graph，也没有 symbol-aware retrieval。

这意味着它在跨文件调用、依赖影响、接口破坏、测试映射等问题上，仍然主要依赖启发式近似，而不是代码库级语义理解。

#### 现状证据

- `ReviewContextBuilder` 主要聚合 file summary、semantic context、relationship hint、repo excerpt
  - `src/main/java/com/codepilot/module/review/context/ReviewContextBuilder.java`
- `SemanticReviewPlanner` 的输入仍然是 patch 派生上下文，不是 repo graph
  - `src/main/java/com/codepilot/module/review/planner/SemanticReviewPlanner.java`
- `AiReviewContextFormatter` 明确声明当前 plan 是：
  - `deterministic, patch-derived, not a full repository graph`
  - `src/main/java/com/codepilot/module/agent/prompt/AiReviewContextFormatter.java`
- `ReviewRagServiceImpl` 仍然是规则 RAG，不是代码库 RAG
  - `src/main/java/com/codepilot/module/agent/service/impl/ReviewRagServiceImpl.java`

#### 目标

建立一个轻量但真实可用的 repo intelligence MVP，为 planner 和 review prompt 提供 symbol-aware context。

#### 范围

- 建立 repository graph 的最小模型
- 支持按以下维度做上下文检索：
  - symbol
  - import
  - route
  - source-test relation
  - dependency
- 接入 `ReviewContextBuilder`
- 接入 `SemanticReviewPlanner`
- 将 graph-derived context 注入 prompt context

#### 建议设计

- 新增 `repo-intelligence` 或 `review.graph` 模块
- 首期优先支持 Java / Spring Boot
- 可先建立这些实体：
  - `SymbolNode`
  - `FileNode`
  - `ImportEdge`
  - `CallEdge`
  - `RouteEdge`
  - `TestRelationEdge`

#### 验收标准

- 对任意 review file，可检索：
  - related symbols
  - direct dependencies
  - related test files
  - likely affected API surface
- `SemanticReviewPlanner` 能利用 graph 提升：
  - `priorityFiles`
  - `crossFileFocuses`
  - `verificationHints`
- 至少新增一批跨文件影响测试 case

#### 非目标

- 不追求第一阶段就做完整 IDE 级 code intelligence 平台
- 不要求一次性支持所有语言

#### 价值

这是项目当前最大的能力天花板。  
没有这项，系统长期仍然只是“上下文增强版 patch reviewer”，而不是“repo-aware AI reviewer”。

---

## Issue 4

### 建议标题

`feat(context): 将关联 Issue / Ticket 上下文注入 Review Planner 与 Prompt 主链路`

### 建议正文

#### 背景

当前系统已经具备“获取 PR 关联 issue”的能力，但从现有实现看，这项能力还主要停留在 API / 展示层，而不是 review 主决策链路的一部分。

也就是说：

- 现在能查到关联 issue
- 但还没有证据表明 planner 或 prompt 真的在消费这部分业务上下文

#### 现状证据

- `GithubClient` 已支持：
  - GraphQL `closingIssuesReferences`
  - PR body closing keyword fallback
  - `src/main/java/com/codepilot/module/git/client/GithubClient.java`
- `ReviewController` 已能返回 `/{taskId}/linked-issues`
  - `src/main/java/com/codepilot/module/review/controller/ReviewController.java`
- 但当前未看到 linked issue 被接入：
  - `ReviewContext`
  - `AiReviewContext`
  - `ReviewContextBuilder`
  - `AiReviewContextFormatter`

#### 问题

当前 reviewer 更容易知道“代码改了什么”，但不一定知道“这次 PR 试图解决什么问题”。  
对 bugfix、issue-driven change、需求修复类 PR 来说，这会让系统缺少任务语义。

#### 目标

把 linked issue / ticket context 纳入 review 主链路，让 planner 和 reviewer 能基于任务背景调整审查焦点。

#### 范围

- 将 linked issue title / state / summary 注入 `ReviewContext`
- 让 planner 能基于 issue context 调整 focus
- 在 prompt 中以受控方式展示 issue context
- 防止 issue 文本污染系统指令

#### 验收标准

- `ReviewContext` 支持 linked issue context
- `SemanticReviewPlanner` 能基于 issue context 生成额外 verification hints 或 risk focus
- prompt regression 覆盖 issue context 注入场景
- issue context 作为 untrusted task context 处理

#### 非目标

- 不拉全量 issue 评论历史
- 不把 issue 原文无预算限制地塞进 prompt

#### 价值

这项能力能把“知道改了什么”推进到“知道为什么而改”，对 review 质量有直接提升。

---

## Issue 5

### 建议标题

`test(github): 建立 GitHub sandbox 端到端验证链路（webhook / summary / inline comment）`

### 建议正文

#### 背景

当前 GitHub 集成层的单元测试和 mock 测试已经不少，但仍然不等于“真实 GitHub 环境下整条链路可靠可用”。

尤其是下面这些能力，只有在真实 sandbox repo 里跑过，才能真正有信心：

- webhook 到 task 的触发链路
- inline comment 的真实落点
- comment 幂等与权限
- linked issue 获取在真实仓库下的行为

#### 现状证据

- `GithubClientTest` 已覆盖不少 mock API case
  - `src/test/java/com/codepilot/module/git/client/GithubClientTest.java`
- `GitHubInlineCommentServiceImplTest` 已覆盖较多 service 逻辑
  - `src/test/java/com/codepilot/module/review/service/impl/GitHubInlineCommentServiceImplTest.java`
- 但目前未见真实 GitHub sandbox E2E

#### 目标

建立一套可重复执行的 GitHub sandbox 端到端验证链路，验证 review 发布能力在真实 GitHub 环境中的可靠性。

#### 范围

- 创建 sandbox repo / PR
- 触发 webhook
- 运行 review
- 验证：
  - summary comment
  - inline comment 行号映射
  - linked issue API
  - 幂等与权限失败场景

#### 验收标准

- 能在 sandbox repo 跑通 review 发布闭环
- 能验证真实 GitHub 上的 inline line mapping
- 能发现 GitHub token / permission / publish 失效问题
- E2E 结果可重复执行并输出记录

#### 非目标

- 不要求纳入默认快速单测套件
- 不要求每次本地开发都执行

#### 价值

这项能力决定 GitHub 集成是不是“看起来能跑”，还是“真实可依赖”。

---

## Issue 6

### 建议标题

`feat(github): 从个人 PAT 架构迁移到 GitHub App installation token 架构`

### 建议正文

#### 背景

当前项目的 GitHub 凭证模式仍然主要依赖全局 token。  
在自部署、小团队使用场景下这可以工作，但如果项目未来要往产品化、企业化方向讲，它会成为明显短板。

#### 现状证据

- 当前 GitHub token 来自全局配置
  - `src/main/resources/application.yml`
- 本地未看到 GitHub App installation token 授权模型

#### 问题

PAT 方案的主要问题：

- 授权粒度粗
- repo / org 隔离弱
- 审计与权限叙事不够强
- 企业接受度有限

#### 目标

建立兼容自部署和未来产品化路线的 GitHub App auth 能力。

#### 范围

- 支持 GitHub App installation token 获取
- 支持 installation token 缓存和刷新
- 区分 repo / org scope
- 保留现有 PAT 作为兼容模式

#### 验收标准

- 支持基于 GitHub App installation token 的 review 流程
- 不再强依赖单个全局 PAT 承载所有仓库授权
- 文档清楚区分：
  - 自部署 PAT 模式
  - GitHub App 模式

#### 非目标

- 不要求第一阶段就建设完整多租户 SaaS 平台

#### 价值

这是产品化和企业可信度方向的关键占位 issue。

---

## 推荐创建顺序

### 第一批：最值得先开的 3 个

1. `feat(eval): 建立端到端 AI Review 标注评测集与回归评测框架`
2. `feat(review): 建立 finding 排序、抑制与评论预算控制机制`
3. `feat(context): 构建 repository graph 与 symbol-aware 上下文检索能力`

### 第二批：能力增强

4. `feat(context): 将关联 Issue / Ticket 上下文注入 Review Planner 与 Prompt 主链路`
5. `test(github): 建立 GitHub sandbox 端到端验证链路（webhook / summary / inline comment）`

### 第三批：产品化占位

6. `feat(github): 从个人 PAT 架构迁移到 GitHub App installation token 架构`

---

## 一句话结论

不是只有 3 个高价值 issue。  
更准确地说：**真正值得保留的大概有 6 个，但当前最该先开的只有前 3 个。**
