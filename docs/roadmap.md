# CodePilot 改进路线图

> 输入：[`docs/scenarios.md`](scenarios.md)（**143 个场景已核实**）+ [`docs/benchmark-ai-review-tools.md`](benchmark-ai-review-tools.md)（**11 个业界系统 × 10 机制**对标）
> **本文件只做设计，不含实现。** 每项都给出：问题 / 方案 / 替代方案与不选理由 / 数据模型与迁移 / 配置开关与默认值 / 测试计划 / 回滚 / 风险。
>
> **写操作一律默认关闭**，并按现有 `fix-*` 开关的风格命名。
>
> 状态图例：**P0** 正确性缺陷（必修）· **P1** 高价值能力 · **P2** 工程完善 · **待决** 本轮不实现

---

## 0. 总览与实施顺序

| 编号 | 改进项 | 优先级 | 覆盖场景 | 依赖 | 预估 |
|---|---|---|---|---|---|
| **R1** | 评论身份分离（修 S1 覆盖缺陷） | **P0** | C7/S1 | 无 | S |
| **R2** | 审查覆盖度透明化（修 S2 假"没问题"） | **P0** | B2/B3/C11/C12/S2 | 无 | S |
| **R3** | DLQ 可运维化（修 S3） | **P0** | I9/S3 | 无 | S |
| **R4** | 多行区间评论 | P1 | C4 | 无 | S |
| **R5** | `suggestion` 块 + 不可应用降级 | P1 | C5 | **R4** | M |
| **R6** | 跨 headSha 记忆与去重 | P1 | E2/E3/A2/A7/C14 | R1 | L |
| **R7** | 误报抑制（resolved thread → DISMISSED） | P1 | E7/E10/G2/G7 | R6 | M |
| **R8** | Agent 限额的用户可见语义 | P2 | F10/F11 | 无 | S |
| **R9** | 对话上下文结构化截断 | P2 | E15/F8 | 无 | S |
| **R10** | 指标补全 + Agent 指标入文档 | P2 | H1–H8 | 无 | S |
| **R11** | 内容分类补全（i18n / vendored / generated） | P2 | B13/B14/B15 | 无 | M |
| **R12** | 配置交叉校验 + 路径级审查指令 | P2 | D4 + 配置体系缺失 | 无 | M |
| **R13** | CI / workflow 变更安全规则 | P2 | B17 | 无 | M |
| **R14** | `pull_request_review_comment` 里的 @mention | P2 | F14 | 无 | M |
| **R15** | 评论生命周期（outdated 检测 + 编辑而非新建） | P2 | C14/C15 | R6 | M |
| **R16** | 修正误导性文案与分类错位 | P2 | B14/B18/C12 | 无 | S |
| **R17** | 构建门禁语义（默认不阻塞） | P2 | 新场景：门禁 | R1/R2 | S |
| **R18** | LLM finding 的自省闸门 | P2 | 空泛/幻觉 finding | R10（成本指标） | M |
| **D1** | 增量审查 | **待决** | A2/A17/H2 | R1/R6 | XL |
| **D2** | 离线评估集扩充 | **待决（已确认要做）** | 指标可信度 | 无 | M |

**建议实施顺序**：`R1 → R2 → R3 →（P0 收口，可独立发布）→ R4 → R5 → R6 → R7 →（P1 收口）→ R16 → R8/R9/R10 → R11/R12/R13/R14/R15`

**理由**：R1/R2/R3 是正确性缺陷且互不依赖，可作为一个独立小版本立刻交付；R4 是 R5 的硬前置（suggestion 必须有行区间）；R6 是 R7 的前置（没有 finding 记忆就无所谓抑制）。

---

# P0 正确性缺陷

## R1 评论身份分离

### 问题

`ReviewReportFormatter.DEFAULT_COMMENT_MARKER`(`ReviewReportFormatter.java:18`) 与 `application.yml:117` 的 `comment-marker` 默认值是**同一个字符串**。`AgentChatTaskRunner.publishAndComplete`(:159-161) 用这个 marker 给 Agent 回复加前缀，而 `GitHubCommentServiceImpl.findExistingCommentId`(:95-105) 找旧摘要评论时**只按 marker 过滤 + 取 `updatedAt` 最大**，没有任何作者或类型判定。

结果：一旦 `CODEPILOT_GITHUB_COMMENT_ENABLED=true`，**Agent 回复与审查摘要会互相覆盖**，静默丢失用户可见内容。`UnavailableCommandHandler:38` 使用同一 marker，问题范围更大。

### 方案

1. **按用途分配独立身份标记**，在 `comment_identity` 层面显式区分：
   - `REVIEW_SUMMARY`：审查摘要，**可更新**
   - `AGENT_REPLY`：Agent 回复，**只追加，永不更新**
   - `COMMAND_NOTICE`：命令拒绝/不可用提示（`UnavailableCommandHandler`）
   - `FIX_RESULT`：修复结果通知（`FixResultCommenter`）
2. `findExistingCommentId` 增加两道过滤：**只匹配指定身份标记** + **只匹配当前认证 bot 作者**（复用 `GithubClient.getAuthenticatedUserLogin()`，该方法已存在并被 `GitHubWebhookService.isBotAuthoredIssueComment` 使用）。
3. 身份标记匹配沿用 PR-Agent 的经验——**只在正文头部 N 行内匹配**，避免正文里引用到标记造成误判（PR-Agent 用 5 行，`comment_identity.py:115-125`）。
4. 保留向后兼容：读旧 marker 时同时接受 `DEFAULT_COMMENT_MARKER`，写新评论时写新标记。

### 替代方案与不选理由

| 方案 | 不选理由 |
|---|---|
| **给 Agent 回复不加任何 marker** | `GitHubWebhookPayloadParser:88` 依赖 marker 过滤 bot 自己的评论以防自循环（`ignored("bot response")`）。去掉 marker 会让 bot 回复触发新的 webhook → 需要另建一套自循环防护 |
| **靠作者判定即可，不加类型标记** | 摘要与 Agent 回复**都是同一个 bot 作者**，仅靠作者无法区分 → 覆盖问题依旧 |
| **把 Agent 回复改成更新同一条评论** | 与产品语义冲突：Agent 多轮对话需要保留历史；且会与 PR-Agent 的 `persistent_comment` 一样把历史压掉 |
| **引入新的 GitHub 账号区分身份** | 运维成本高，需要第二套凭据与 App 安装，收益不成比例 |

### 数据模型与迁移

**无数据库变更。** 仅新增常量与配置默认值。`codepilot.github.comment-marker` 语义收窄为"审查摘要的身份标记"。

### 配置开关与默认值

| 配置 | 默认 | 说明 |
|---|---|---|
| `codepilot.github.comment-marker` | 保持现有值 | 审查摘要身份标记（向后兼容） |
| `codepilot.github.agent-reply-marker` | `<!-- codepilot-agent-reply -->` | **新增**，Agent 回复身份标记 |
| `codepilot.github.command-notice-marker` | `<!-- codepilot-command-notice -->` | **新增** |
| `codepilot.github.comment-identity-header-lines` | `5` | **新增**，身份标记只在正文前 N 行内匹配 |

### 测试计划

- `GitHubCommentServiceImplTest` 新增：**同 PR 同时存在 Agent 回复与摘要时，更新摘要不会命中 Agent 回复**（这是当前缺陷的直接回归测试）
- 新增：不同 bot 作者的同 marker 评论不被命中
- 新增：正文中间出现 marker 字符串不被误判为身份标记（只匹配头部 N 行）
- 新增：旧 `DEFAULT_COMMENT_MARKER` 评论仍能被识别并更新（向后兼容）
- 沙箱 E2E：真实 PR 上先 `@x-pilotx` 再触发审查，断言两条评论**都存在且内容正确**

### 回滚

纯代码改动 + 新配置项。回滚 = revert 该提交；已发出的旧标记评论仍可被兼容逻辑识别，无数据损坏残留。

### 风险

- **中**：身份标记匹配收紧后，若历史评论的标记位置不在前 5 行（例如有人在正文前插入了引用），会识别不到而新建评论 → 表现为"摘要评论变多"。缓解：`comment-identity-header-lines` 可调，且默认 5 与 PR-Agent 一致。
- **低**：忘记给某个新评论路径分配身份 → 该路径的评论可能再次被覆盖。缓解：把身份标记改为**枚举 + 编译期强制**，而不是散落的字符串常量。

---

## R2 审查覆盖度透明化

### 问题

`ReviewReportFormatter.formatMarkdown`(:44-76) 只渲染 issue 列表：
- **从不提及被跳过的文件**——尽管 `review_file.skipped` / `skip_reason` 与 `review_task.total_files` 都已持久化
- `:51-54` 在 `visibleIssues.isEmpty()` 时直接输出**「未发现问题。」**

后果：一个 200 文件的 PR 只审了 5 个，摘要读起来像全审过；更严重的——若规划器跳过了**全部**文件（例如所有 patch 都超过 `max-patch-chars-per-file`），摘要会输出**「未发现问题。」**，等于给出一张**假的体检合格报告**。

对标结论：PR-Agent `enable_review_coverage_footer` **默认开启**，明确输出 "The following files were not included in this review because of the token budget"；Graphite 超限时直接 `Not running: ...` 并说明原因；Graphite 还专门为抓不到内容的文件插占位说明，**避免"跳过"被误当成"干净"**。我们是这三个里唯一会输出假"没问题"的。

### 方案

重构 `ReviewReportFormatter`，在摘要中固定输出一个**覆盖度区块**（而非只在没有 issue 时输出"未发现问题"）：

```
## 审查覆盖
- 本次审查 X / Y 个变更文件（跳过 Z 个）
- 未审查文件及原因（按原因分组，超过 20 条折叠进 <details>）：
  - 预算超限（N 个）：a.java, b.java …
  - 单文件 patch 超限（N 个）：c.java …
  - 生成物 / 锁文件 / 空 patch（N 个）
  - 无法读取内容（N 个）：< 需人工审查 >
- 被抑制的问题：M 条（原因分布）
- 本 PR 第 K 次审查，本次审查 commit：<short sha>
```

同时修正空结果语义：
- 有变更文件但**一个都没审** → 输出 **「⚠️ 未能审查任何文件」+ 原因**，绝不输出"未发现问题"
- 确实全部审过且无问题 → 才输出"未发现问题"

`review_file.skip_reason` 目前是自由文本，需要归类成**结构化枚举**才能分组展示（见数据模型）。

### 替代方案与不选理由

| 方案 | 不选理由 |
|---|---|
| **只在"有跳过"时输出一行汇总数字** | 用户无法知道**哪些**文件没审，无法判断风险是否落在未审范围内。Graphite 的教训是必须点名 |
| **把未审文件做成 inline 评论** | 未审文件往往没有可评论的 diff 行；且会消耗 inline 预算、加剧刷屏 |
| **单独发第二条"覆盖度"评论** | 违反"Summary marker 幂等"设计，且用户要在两条评论间来回看。PR-Agent 也是放在同一条 review 正文里 |
| **超限时直接拒绝审查整个 PR**（Graphite 做法） | 我们的预算截断是**按文件优先级**做的，部分审查仍有价值；全拒绝会让大 PR 完全得不到反馈。应保留部分审查 + 明确上报 |

### 数据模型与迁移

新增迁移 **V18**：

```sql
ALTER TABLE review_file ADD COLUMN IF NOT EXISTS skip_category VARCHAR(32);
CREATE INDEX IF NOT EXISTS idx_review_file_task_skip
    ON review_file (task_id, skipped, skip_category);
```

`skip_category` 取值（枚举，与 `skip_reason` 并存以保留人类可读文本）：
`EMPTY_PATCH` / `PATH_EXCLUDED` / `PATCH_TOO_LARGE` / `FILE_COUNT_BUDGET` / `TOTAL_PATCH_BUDGET` / `UNREADABLE` / `BINARY`

`ReviewFilePlanner` 的每个 `markSkipped` 调用点补齐 category；`ReviewTask` 已有 `total_files`，无需新增列。

### 配置开关与默认值

| 配置 | 默认 | 说明 |
|---|---|---|
| `codepilot.review.coverage-report-enabled` | **`true`** | 是否输出覆盖度区块。**默认开**（对标 PR-Agent） |
| `codepilot.review.coverage-max-listed-files` | `20` | 超过则折叠进 `<details>` |
| `codepilot.review.fail-on-zero-coverage` | `false` | 若为 true，一个文件都没审时把任务标为需要人工关注（而非 SUCCESS） |
| `codepilot.review.min-coverage-ratio` | `0.5` | **（对标补充 7）** 已审文件数 / 变更文件数低于此比例时，摘要顶部加醒目告警，并让 R17 的 gate 保持 `neutral` 而非 `success`。理由：Ellipsis 把"预算溢出的 reviewer"直接判为失败，说明**覆盖不足本身是一种失败状态**，不能静默通过 |

### 测试计划

- `ReviewReportFormatterTest` 新增：**全部文件被跳过时，摘要必须包含"未能审查任何文件"且不得包含"未发现问题"**（S2 的直接回归测试）
- 新增：部分跳过时，覆盖度区块列出正确文件数与分类
- 新增：跳过文件超过 `coverage-max-listed-files` 时折叠
- 新增：无跳过且无 issue 时仍输出"未发现问题"
- `ReviewFilePlannerTest` 新增：每个跳过路径写入正确的 `skip_category`
- 集成测试：`review_file.skip_category` 正确落库（真实 PostgreSQL）

### 回滚

开关可即时关闭（`coverage-report-enabled=false` 回到旧行为）。迁移为纯新增列，向后兼容，无需回滚脚本。

### 风险

- **低**：摘要变长。缓解：分类分组 + `<details>` 折叠 + 可配置上限。
- **低**：`skip_category` 归类不准会让用户误解原因。缓解：category 与 reason 文本同时保留，UI 优先展示 reason。

---

## R3 DLQ 可运维化

### 问题

死信队列已配置（`RabbitMqConfig:39,82`，`default-requeue-rejected=false`），但全仓库**只有 2 个 `@RabbitListener`，都指向主队列**——DLQ 没有任何消费者。最终失败的消息进入 DLQ 后**永久滞留，无告警、无重放、无可见性**（除 RabbitMQ 管理台）。

`docs/architecture.md` 却写着"RabbitMQ retry + DLQ 保存最终消费失败消息，便于排查"——**文档承诺了代码没有提供的能力**。

### 方案

1. **持久化死信记录**：DLQ 消费者把消息落库到 `dead_letter_message`（保留原始 payload、失败类、错误摘要、attempt 次数、首次/末次时间），消息在 DLQ 中被 ack 掉。
2. **运维接口**（`/api/admin/dead-letters`，受 API Key 保护）：
   - `GET` 分页查询 + 按队列/时间/错误类型过滤
   - `POST /{id}/replay` 重新投递（复用原 message，重置幂等状态后入队）
   - `POST /{id}/discard` 标记为已处理
3. **告警**：新增指标 `codepilot.dlq.messages`（按队列打 tag），并提供 `codepilot.dlq.backlog` gauge；文档说明如何基于 `/actuator/metrics` 告警。

**重放的安全性**：重放必须走既有的幂等入口（`ReviewTaskServiceImpl.processTask` 会 claim + token 校验；`PrCommandTaskServiceImpl.processCommandTask` 同理），因此重复重放不会导致重复执行——这是现有设计的自然收益，不需要新机制。

### 替代方案与不选理由

| 方案 | 不选理由 |
|---|---|
| **只在文档里删掉"便于排查"的承诺** | 治标不治本：消息仍然不可恢复，运维事故依然无法处理 |
| **用 RabbitMQ 管理台手工重放** | 无法审计（谁在什么时候重放了什么）、无法批量、无法记录原因 |
| **让 DLQ 消费者直接自动重投 N 次** | 会与 `max-attempts=3` + DLQ 的设计意图冲突。进 DLQ 意味着**已经判定为需要人工介入**，自动重投会把毒消息变成无限循环 |
| **引入外部死信服务（如 SQS DLQ）** | 与"不引入不必要外部依赖"的项目取向冲突，且 PostgreSQL 已足够 |

### 数据模型与迁移

新增迁移 **V19**：

```sql
CREATE TABLE IF NOT EXISTS dead_letter_message (
    id BIGSERIAL PRIMARY KEY,
    source_queue VARCHAR(128) NOT NULL,
    message_type VARCHAR(64) NOT NULL,      -- REVIEW_TASK / PR_COMMAND_TASK
    payload TEXT NOT NULL,
    payload_digest VARCHAR(64) NOT NULL,     -- sha256，用于识别同一消息反复死亡
    error_type VARCHAR(128),
    error_message TEXT,
    attempt_count INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',  -- PENDING / REPLAYED / DISCARDED
    replayed_at TIMESTAMP,
    replayed_by VARCHAR(128),
    first_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_dead_letter_status ON dead_letter_message (status, last_seen_at);
CREATE UNIQUE INDEX IF NOT EXISTS uq_dead_letter_digest_status
    ON dead_letter_message (payload_digest, status);
```

`payload_digest + status` 唯一索引让同一条消息反复进 DLQ 时只累加 `attempt_count`，不产生重复行。

### 配置开关与默认值

| 配置 | 默认 | 说明 |
|---|---|---|
| `codepilot.dlq.consumer-enabled` | `true` | 是否消费 DLQ 落库（落库本身无副作用，默认开） |
| `codepilot.dlq.replay-enabled` | **`false`** | 是否开放重放接口。**默认关**（属写操作） |
| `codepilot.dlq.retention-days` | `30` | 死信记录保留天数，配合清理任务 |

### 测试计划

- 单元：DLQ 消费者落库正确、同一消息重复死亡只累加不新增
- 新增 Testcontainers 测试：**真实 RabbitMQ 上投递毒消息 → 断言消息进入 `codepilot.review.task.dlq` → 断言落库到 `dead_letter_message`**（现有 `ReliableReviewInfrastructureIntegrationTest` 已验证死信路由，这里补断言的落库环节）
- 重放：`replay-enabled=true` 时重放成功入队；`false` 时接口返回 403/404
- 重放幂等：对同一死信重放两次，任务只执行一次（复用既有 claim 机制）

### 回滚

`dlq.consumer-enabled=false` 即停止消费（消息留在 DLQ 不丢）。表为纯新增。回滚无需数据迁移。

### 风险

- **中**：DLQ 消费者把消息 ack 后落库失败 → **消息丢失**。缓解：必须先落库成功再 ack；落库失败则不 ack 让消息留在 DLQ（宁可重复也不丢）。
- **低**：`payload` 可能含敏感信息（PR 标题、评论正文）。缓解：落库前走 `SensitiveDataSanitizer.redactAndTruncate`，并在 `SECURITY.md` 补充说明。

---

# P1 高价值能力

## R4 多行区间评论

### 问题

`GithubClient.createPullRequestInlineComment`(:349-385) 的请求体只写 `line` + `side`(:376-377)，**无 `start_line` / `start_side`** → 所有 inline 评论被压成单行，一个跨多行的 finding 只能锚在其中一行。

这也是 **R5 的硬前置**：CodeRabbit 明确要求 suggestion 必须"anchored to real lines on the **new** side of the diff, with a **valid start-to-end range**"。

### 方案

1. `ReviewIssue` 增加 `startLine` / `endLine` 语义（复用现有 `lineNumber` 作为落点，新增可选区间）。
2. `DiffLineMapper` 增加**区间映射**能力：给定 (startLine, endLine) 判断是否**整体**落在同一 hunk 的可评论行内；只有整体可评论才发区间评论，否则降级为单行（使用 `lineNumber`）。
3. `GithubClient.createPullRequestInlineComment` 增加重载，支持 `startLine` + `startSide`；两者同时存在时才写入请求体（`start_line` 必须与 `line` 在**同一侧**，GitHub 的约束）。

### 替代方案与不选理由

| 方案 | 不选理由 |
|---|---|
| **继续只用单行** | suggestion 无法实现，且跨行 finding 的表达力受限 |
| **把区间信息写进评论正文（"第 10-15 行"）** | 无法获得 GitHub 的原生高亮与建议应用能力，等于放弃了 R5 的价值 |
| **总是尝试区间，失败就整条丢弃** | 会因定位问题丢失有效 finding。应降级为单行而不是丢弃（与 PR-Agent 的降级哲学一致） |

### 数据模型与迁移

新增迁移 **V20**：

```sql
ALTER TABLE review_issue ADD COLUMN IF NOT EXISTS start_line INT;
ALTER TABLE review_issue ADD COLUMN IF NOT EXISTS end_line INT;
ALTER TABLE review_issue ADD COLUMN IF NOT EXISTS line_side VARCHAR(8);
```

`line_side` 显式持久化 LEFT/RIGHT，避免重复计算 `DiffLineMapper`（也顺带解决 C12 里"文案与实际判定范围不符"的问题）。

### 配置开关与默认值

| 配置 | 默认 | 说明 |
|---|---|---|
| `codepilot.review.multiline-comment-enabled` | `true` | 是否尝试区间评论。读侧能力，默认开 |

### 测试计划

- `DiffLineMapperTest` 新增：区间整体落在 hunk 内 → 可评论；跨 hunk → 降级单行；跨 side → 降级单行
- `GithubClientTest` 新增：请求体在区间模式下包含 `start_line`/`start_side`；单行模式不包含
- `GitHubInlineCommentServiceImplTest` 新增：多行 finding 生成多行评论；降级路径生成单行评论

### 回滚

开关关闭即回到单行行为。新增列为 nullable，无数据迁移。

### 风险

- **中**：GitHub 对 `start_line` 与 `line` 必须同侧、且都在 diff 内的约束较严，越界会 422。缓解：`DiffLineMapper` 先在本地校验，且保留 422 时的单行降级重试（PR-Agent 的 fallback 思路）。

---

## R5 `suggestion` 块 + 不可应用降级

### 问题

`GitHubInlineCommentBodyBuilder` 不生成 ` ```suggestion ` 围栏，全仓库无 `suggestion` 关键字 → **无法一键应用修复**，这是 AI review 最高价值的交互，也是所有对标系统的标配。

### 方案

1. `AiReviewIssue` / `ReviewIssue` 增加 `suggestedCode`（可选）字段，由 LLM 在结构化输出中提供**替换后的完整代码块**（而非 diff）。
2. **可应用性校验（核心）**：`ReviewSuggestionValidator` 在发布前校验
   - `startLine..endLine` 必须整体落在 diff **新侧**的可评论行内
   - `suggestedCode` 的行数与区间行数**不要求相等**（GitHub 允许），但**必须非空**
   - 区间内**不得包含被删除的行**（CodeRabbit 明确"对已删除行拒绝生成"）
3. **降级策略（抄 PR-Agent）**：校验失败时，把 ` ```suggestion ` 块**改写为 ` ```diff ` 块**，保留多行展示但明确不可一键应用（PR-Agent 注释原文："make the suggestion non-committable, yet multi line"）。
4. 422 兜底：发布失败时剥掉 suggestion 部分、只锚第一行重试（PR-Agent 的 `_publish_inline_comments_fallback_with_verification` 思路）。

### 替代方案与不选理由

| 方案 | 不选理由 |
|---|---|
| **不做校验，直接发 suggestion** | 会发出**应用不了**的建议，用户点一下报错，比没有 suggestion 更伤信任 |
| **只发 `diff` 块（永不做 suggestion）** | 放弃了最高价值交互。应"能应用就给 suggestion，不能就给 diff" |
| **让 LLM 直接输出 unified diff 并自己应用** | diff 的行号对齐极易出错，且 GitHub 的 suggestion 语法要求的是**替换后的内容**而不是 diff |
| **在服务端本地应用 patch 校验后再发**（PR-Agent 的 fix 路径做法） | 成本高（需要 clone 仓库）、且与"审查不发写操作"的边界冲突（我们已有 fix 链路做这件事，但那是独立审批流程） |

### 数据模型与迁移

新增迁移 **V21**：

```sql
ALTER TABLE review_issue ADD COLUMN IF NOT EXISTS suggested_code TEXT;
ALTER TABLE review_issue ADD COLUMN IF NOT EXISTS suggestion_applicable BOOLEAN DEFAULT FALSE;
```

### 配置开关与默认值

| 配置 | 默认 | 说明 |
|---|---|---|
| `codepilot.review.suggestion-enabled` | **`false`** | 是否生成 `suggestion` 块。**默认关**（属 GitHub 写侧交互能力，需先沙箱验证） |
| `codepilot.review.suggestion-max-lines` | `20` | 单个 suggestion 的最大行数，超限降级为 diff 块 |
| `codepilot.review.suggestion-fallback-to-diff` | `true` | 校验失败时降级为 diff 块而不是丢弃 |

### 测试计划

- `ReviewSuggestionValidatorTest`：区间含删除行 → 拒绝；区间越界 → 拒绝；区间在新侧且非删除行 → 通过
- `GitHubInlineCommentBodyBuilderTest`：通过时生成 ` ```suggestion `；失败且 `fallback-to-diff=true` 时生成 ` ```diff `
- 沙箱 E2E：在测试仓库真实 PR 上发出 suggestion，**并用 GitHub API 验证其可被应用**（这是"保证可应用"的唯一可信证据）
- 422 兜底：mock 422 响应，断言降级重试只锚第一行

### 回滚

`suggestion-enabled=false` 即完全关闭（默认就是关）。新增列 nullable。

### 风险

- **高**：suggestion 应用错误会**直接改坏用户代码**（即使需要用户点击确认）。缓解：默认关闭 + 严格校验 + 只在完全确定的区间生成；`SECURITY.md` 需补充这一风险的说明。
- **中**：LLM 输出的替换代码可能与实际文件上下文不匹配（我们只给了 patch，没有给完整文件）。缓解：`suggestion-max-lines` 限制在 patch 可见范围内，超出即降级。

---

## R6 跨 headSha 记忆与去重

### 问题

新 headSha = 全新任务、全新问题集。系统**不知道上一次审查提过什么**（E2/E3）。而 `synchronize` 是全量重审（A2），因此每次 push 都会把同一批问题**再评论一遍**。

现有 `GitHubInlineCommentFingerprintExtractor` 只能在同一任务内去重（C13），跨任务无效。

### 方案

在 PostgreSQL 中维护**PR 级 finding 记忆**，跨 headSha 比对：

1. 新增表 `review_finding_memory`，键为 `(repo_owner, repo_name, pr_number, finding_fingerprint)`。
2. **指纹算法**（借鉴 PR-Agent，但适配我们的模型）：
   - `finding_fingerprint = sha256(filePath | normalizedTitle | normalizedIssueType)[:16]`
   - **绝不包含行号**——行号会随 commit 漂移（PR-Agent 明确因 position 漂移而只用 file+正文）
   - 另存 `code_fingerprint = sha256(filePath | normalizedSnippet)[:16]`，两者 **OR 匹配**，以覆盖"同问题换了措辞"与"同代码换了描述"两种 LLM 复述方式
3. **生命周期状态**（抄 PR-Agent 的状态机）：
   `ACTIVE` → 本次仍存在
   `RESOLVED` → 本次未出现，且**满足解决条件**
   `DISMISSED` → 人工 resolve 了我们的 thread（见 R7）
   `reopened_count` → 再次出现的次数
4. **解决条件（关键设计，直接抄 PR-Agent 的 `allow_resolution`）**：只有同时满足才允许标 RESOLVED——
   - `previousHeadSha != currentHeadSha`
   - 本次审查是**完整**的：没有文件因预算被跳过、没有 LLM 失败的文件、没有超限被丢弃的 finding
   
   **理由**：如果这次没看全，某个问题"没出现"可能只是因为它所在文件没被审到——把它标成"已修复"是错误结论。
5. **发布策略**：
   - `ACTIVE` 且**上次已提过** → **不在 GitHub 重复评论**（这是去重的核心收益）
   - `ACTIVE` 且**位置或代码已变** → 允许重发（`code_fingerprint` 不同）
   - `reopened_count > 0` → 在摘要中标注"该问题第 N 次出现"
   - `RESOLVED` → 在摘要的折叠区块中列出

### 替代方案与不选理由

| 方案 | 不选理由 |
|---|---|
| **PR-Agent 做法：状态序列化成隐藏标记藏在评论正文里** | PR-Agent 这么做是因为它**是无服务端的 CLI/GitHub App，没有数据库**。**我们有 PostgreSQL 且已有完整的 task/issue 模型**，用 DB 更合适：可查询（"这个仓库最常被提的问题"）、不怕人删评论、可跨 PR 聚合、可做指标。**这是本项目与 PR-Agent 架构差异导致取舍反转的典型例子** |
| **直接编辑上一条 inline 评论而不是发新的** | inline 评论锚在 diff position 上，新 commit 后旧位置失效，编辑会得到错位的评论（PR-Agent 也因这个原因不敢用 position） |
| **只靠 GitHub 的 "outdated" 状态判断** | outdated 只能说明行变了，不能说明问题是否已修复；且不解决"同一问题换了行号"的情况 |
| **用 LLM 判断"上次的问题这次修了吗"** | 成本高、不确定；而"完整审查 + 未再出现"是**可确定**的判据 |

### 数据模型与迁移

新增迁移 **V22**：

```sql
CREATE TABLE IF NOT EXISTS review_finding_memory (
    id BIGSERIAL PRIMARY KEY,
    repo_owner VARCHAR(128) NOT NULL,
    repo_name VARCHAR(128) NOT NULL,
    pr_number INT NOT NULL,
    finding_fingerprint VARCHAR(64) NOT NULL,
    code_fingerprint VARCHAR(64),
    issue_type VARCHAR(64),
    file_path VARCHAR(512),
    severity VARCHAR(32),
    state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE / RESOLVED / DISMISSED
    reopened_count INT NOT NULL DEFAULT 0,
    first_task_id BIGINT,
    last_task_id BIGINT,
    first_seen_head_sha VARCHAR(128),
    last_seen_head_sha VARCHAR(128),
    resolved_at_head_sha VARCHAR(128),
    suppressed_reason VARCHAR(255),
    first_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_finding_memory_key
    ON review_finding_memory (repo_owner, repo_name, pr_number, finding_fingerprint);
CREATE INDEX IF NOT EXISTS idx_finding_memory_state
    ON review_finding_memory (repo_owner, repo_name, pr_number, state);
```

配套新增 `review_task_result_store` 的写入步骤：在 `replaceAndComplete` 的**同一事务**内做记忆的 upsert + reconcile，保证记忆与结果集原子一致。

### 配置开关与默认值

| 配置 | 默认 | 说明 |
|---|---|---|
| `codepilot.review.finding-memory-enabled` | **`false`** | 是否启用跨评审记忆。**默认关**（涉及新表与发布行为变化，先灰度） |
| `codepilot.review.suppress-repeated-findings` | `true` | 记忆启用时，是否跳过重复评论（仅当记忆开启才生效） |
| `codepilot.review.allow-resolution` | `true` | 是否允许把未再现的 finding 标为 RESOLVED（需满足完整审查条件） |

### 测试计划

- 指纹稳定性：同 finding 在两个不同 headSha 上算出相同指纹；行号变化不影响指纹
- OR 匹配：仅正文措辞变化 → 命中；仅建议代码变化 → 命中；两者都变 → 不命中
- **解决条件**：不完整审查（有跳过文件）时，未再现的 finding **不得**被标 RESOLVED（这是最重要的回归测试）
- 去重：同一 finding 在第二次审查中不再重复发评论
- `reopened_count` 递增，摘要正确标注"第 N 次出现"
- 真实 PostgreSQL 集成测试：记忆 upsert 与结果替换在同一事务内，回滚时记忆不变

### 回滚

`finding-memory-enabled=false` 立即回到旧行为（不再读写记忆表）。表为纯新增，可保留。若需彻底回滚，drop 表即可——**因为记忆是可重建的派生数据，不承载唯一事实**（这一点是刻意的：避免记忆表成为单点真相）。

### 风险

- **高**：指纹设计不当会导致**漏报**——两个不同问题算出同一指纹，第二个被当成重复而**不评论**。这比重复评论更危险。缓解：指纹只用 file + issueType + 归一化标题，**不掺入模糊语义**；并新增"指纹碰撞率"指标；提供 `suppress-repeated-findings=false` 的逃生舱
- **中**：记忆表与 GitHub 实际评论状态可能不一致（人能删评论）。缓解：记忆只用于**抑制重复**，不作为"评论存在"的权威依据；发布前仍查一次 GitHub 实际评论
- **中**：RESOLVED 判定错误会让用户以为问题已修。缓解：严格遵循"完整审查才允许 resolve"，且在摘要中区分"已修复（未再现）"与"未再现（可能未覆盖）"

---

## R7 误报抑制

### 问题

**整个 G 维度（协作与反馈）为零**：无 dismiss、无 resolve、无 reaction 处理。人标记过"这是误报"的问题，下次审查照样提。

### 方案

**采用 PR-Agent 的方案：把"人 resolve 了我们开的 thread"作为抑制信号**，而不是监听 👍/👎 reaction。

1. 发布 inline 评论后记录其 `comment_id` 与 `finding_fingerprint`。
2. 审查开始前，通过 **GitHub GraphQL** 读取本 PR 的 review threads 状态（`reviewThreads { isResolved, comments { author, body } }`），筛选：
   - `isResolved == true`
   - 该 thread 的**首条评论作者是本 bot**
3. 命中的 thread 对应的 finding → 在 `review_finding_memory` 中标 `DISMISSED`，并在**下一次 LLM 调用的 prompt 中注入**"以下问题已被维护者判定为无需处理，请勿再提"（借鉴 PR-Agent 的 `render_previous_findings(..., dismissed)`）。
4. 摘要中把 DISMISSED 的问题收进折叠区块，标注"已被维护者关闭"。
5. **人类手动 resolve 的线程不释放抑制**（PR-Agent 的 `store.release()` 只对"过期线程清扫"释放）——即人的 resolve 是**持续有效**的。

### 替代方案与不选理由

| 方案 | 不选理由 |
|---|---|
| **监听 👍/👎 reaction**（Greptile 做法） | 需要额外订阅 `reaction` webhook；reaction 语义模糊（"这评论有用"≠"这是误报"）；且人在看代码时更自然的动作是 **resolve thread** 而不是去点 emoji |
| **让用户回复 `@x-pilotx 忽略这个问题`** | 依赖自然语言解析，不如 GitHub 原生状态确定；可以作为**补充**入口，但不应作为唯一机制 |
| **仓库级 suppression 配置文件**（Semgrep 的 `nosemgrep` 风格） | 对"规则类"误报有效，对我们大量由 LLM 产生的语义类 finding 不适用（无法用规则表达） |
| **PR-Agent 的 key-issue 标记门槛** | 它要求 thread 带 key-issue 标记，是因为它只对 key issues 做生命周期管理。我们有完整的 `review_issue` 表，可以覆盖全部 finding，不需要这个门槛 |

### 数据模型与迁移

复用 **R6 的 `review_finding_memory`**（新增 `DISMISSED` 状态，字段已预留 `suppressed_reason`）。另外新增一张轻表记录我们发布的评论身份：

新增迁移 **V23**：

```sql
CREATE TABLE IF NOT EXISTS review_published_comment (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    github_comment_id BIGINT NOT NULL,
    finding_fingerprint VARCHAR(64),
    channel VARCHAR(16) NOT NULL,          -- INLINE / SUMMARY
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_published_comment_github_id
    ON review_published_comment (github_comment_id);
CREATE INDEX IF NOT EXISTS idx_published_comment_task
    ON review_published_comment (task_id);
```

**这张表还顺带解决了 I8（部分发布成功无法精确记录）**：现在可以知道"哪几条 inline 评论已经发出去了"。

### 配置开关与默认值

| 配置 | 默认 | 说明 |
|---|---|---|
| `codepilot.review.dismissal-sync-enabled` | **`false`** | 是否读取 thread resolution 做抑制。**默认关**（需要 GraphQL 权限） |
| `codepilot.review.dismissal-prompt-injection` | `true` | 是否把 DISMISSED 列表注入 prompt |
| `codepilot.review.dismissal-max-prompt-chars` | `4000` | 注入上限（PR-Agent 用 8000） |

### 测试计划

- GraphQL 客户端：mock 响应，正确解析 `reviewThreads.isResolved`
- **只认自己开的 thread**：人类开的且已 resolved 的 thread **不得**触发抑制
- 抑制生效：被 DISMISSED 的 finding 不再出现在新一轮评论中
- prompt 注入：DISMISSED 列表出现在下一次 LLM 输入里，且被标记为不可信内容块
- 上限生效：超过 `dismissal-max-prompt-chars` 时截断（按条截断而非字符盲截）
- 权限降级：GraphQL 不可用（token 无权限）时**不得让审查失败**，只记录 warn 并按无抑制处理

### 回滚

`dismissal-sync-enabled=false` 即停止读取（已写入的 DISMISSED 状态保留但不再影响发布——需在代码中确认抑制判定同时受开关控制）。

### 风险

- **中**：误把"用户顺手 resolve 了所有 thread 以便看清 diff"当成"全部是误报" → 导致大面积漏报。缓解：摘要中明确展示"本次因维护者关闭而跳过 N 条"，让人能发现异常；且提供开关
- **中**：GraphQL 权限不足或 rate limit 会阻塞审查。缓解：**抑制同步必须是旁路**——失败只降级，绝不阻塞主流程（这是硬性设计约束）
- **低**：`review_published_comment` 与 GitHub 实际状态不一致（评论被删）。缓解：该表只用于**辅助**，不作为发布决策的唯一依据

---

# P2 工程完善

## R8 Agent 限额的用户可见语义

**问题**：模型轮次 / 工具调用 / continuation / 截止时间超限，全部抛 `IllegalStateException`（`AgentRunService:60,73,90,135`），被 `AgentChatTaskRunner:132-151` 捕获后用户只看到通用文案「暂时无法完成本次 Agent 请求，请稍后重试」，**不说明真实原因**；且这类错误被当作可重试处理，重试仍会撞同一上限。

**方案**：定义 `AgentLimitExceededException`（携带 `limitType` 与 `currentValue`/`limitValue`），在 `AgentChatTaskRunner` 中单独 catch，输出**明确且可行动**的回复，例如"本次请求已达到工具调用上限（12 次），请把问题拆小后重试"；并把该异常标记为**不可重试**（`PrCommandTaskFailureHandler` 需增加终态判定）。

**替代方案**：直接提高限额——不选，限额是成本与失控保护；只改文案而不区分可重试性——不选，会浪费重试配额并延迟用户得到真实反馈。

**数据模型**：无。
**配置**：复用现有 `codepilot.agent-runtime.*`，无新增。
**测试**：分别触发 4 种限额，断言（a）用户收到含具体限额类型的回复（b）任务进入终态不再重试（c）`agent_run` 状态正确。
**回滚**：纯代码改动，revert 即可。
**风险**：低。需注意不要因为区分异常类型而误把真正的可重试故障（如 LLM 超时）标成终态——因此只对四个明确的上限异常做终态处理。

## R9 对话上下文结构化截断

**问题**：`AgentConversationContextBuilder:57` 用 `result.substring(result.length() - maxChars)` **盲截尾部字符**，会把一条消息切成半句；无摘要、无消息边界感知。

**方案**：改为**按 turn 边界**裁剪——从最近往前累加整条 turn，直到超出预算；**最后一条**若单独超预算，才对它做截断并加显式 `…[truncated]` 标记。预算不足时在开头标注 `conversationContextStatus: PARTIAL (N of M turns)`。

**替代方案**：用 LLM 做摘要——不选，引入额外调用与延迟，且对话上下文本身价值有限；直接丢弃全部历史——不选，会丢失多轮追问的连续性。

**数据模型**：无。**配置**：复用 `conversation-max-turns` / `conversation-max-chars`；新增 `conversation-truncation-mode`（`turn` 默认 / `chars` 兼容旧行为）。
**测试**：断言不会出现半句；turn 数不足时状态标记为 PARTIAL；单条超预算时有截断标记。
**回滚**：`conversation-truncation-mode=chars` 回到旧行为。
**风险**：低。turn 边界裁剪可能比字符裁剪保留更少内容，需在文档中说明这是有意的取舍（完整性优先于信息量）。

## R10 指标补全

**问题**：无缓存命中率（H3）、无队列积压（H6）、无 token 成本（H2/H8）；`AgentMetrics` 的三个指标（`codepilot.agent.model.turns` / `.tool.calls` / `.run.transitions`）**未出现在 `docs/architecture.md` 的指标清单里**。

**方案**：
1. 补指标：`codepilot.review.llm.cache`（hit/miss）、`codepilot.rag.cache`（hit/miss）、`codepilot.review.queue.backlog`（gauge，从 RabbitMQ `QueueDeclarePassive` 读 message count）
2. **成本与 token（对标补充 13 已有源码级先例，字段设计照抄 PR-Agent 的 `run_output.py`）**：
   - `codepilot.review.llm.tokens`（Counter，按 `prompt` / `completion` / **`cache_read`** / **`cache_creation`** 分别打 tag）
   - `codepilot.review.cost`（Counter，按 `model` 打 tag；并**显式区分"完全不可定价"与"部分可定价"**，对应 PR-Agent 的 `cost_status = unavailable` / `partial`）
   - 展示细节：成本四舍五入后若为 0 但实际为正，**显示 `<$0.01` 而不是 0**（`_format_usd` 的做法），否则会让人误以为不花钱
   - **`cache_read` 必须单独打 tag**：它是判断"缓存到底有没有省钱"的唯一依据。注意 PR-Agent 的 `cache_read` 来自 **provider 侧 prompt caching**，而我们的 `ai_review_cache` 是**应用级响应缓存**，两者语义不同——因此指标要分两个维度：`codepilot.review.llm.cache`（我们的应用缓存命中）与 `cache_read` tag（provider 侧缓存命中）
3. 把 `codepilot.agent.*` 三个指标补进 `docs/architecture.md` 指标清单
4. 新增 `docs/observability.md`：列出全部指标、类型、含义、以及"没有 Prometheus 时的查询方式"
5. 新增 **dry-run 模式**（`codepilot.review.publish-dry-run`，默认 `false`）：走完整审查与发布决策链路但**不真正调用 GitHub 写接口**，用于验证 R1/R5/R17 的发布逻辑（Cursor Bugbot 已提供同等能力，见对标补充 10）

**替代方案**：直接引入 Prometheus + Grafana——**本轮不选**，属于部署栈变更，应与"是否做生产化"一起决策；先补齐指标本身，暴露方式后置。

**数据模型**：无。**配置**：无新增（沿用 Actuator）。
**测试**：`SimpleMeterRegistry` 断言各计数器在对应路径被触发。
**回滚**：纯增量。
**风险**：低。RabbitMQ backlog gauge 需要管理连接，失败时必须降级为不暴露而不是让应用启动失败。

## R11 内容分类补全

**问题**：i18n/资源包无分类（B13）；生成代码只靠 `dist/`/`target/`/`build/` 目录名（B14）；`vendor/`、`third_party/`、`node_modules/` **完全不在跳过列表**（B15）。

**方案**：
1. `ReviewFileClassifier` 新增 `isI18nPath`（`/i18n/`、`/locale/`、`/locales/`、`messages*.properties`、`/lang/`），**归为低优先级而非跳过**（翻译错误也是真问题）
2. `shouldSkipPath` 补 `vendor/`、`third_party/`、`node_modules/`、`.git/`、`*.pb.go`、`*_pb2.py`、`*.g.dart`、`*.designer.cs`、`*.generated.*`
3. **借鉴 Graphite**：读取 GitHub 的 `linguist-generated` 标注来识别生成物，而不是自维护路径规则。需确认 REST `pulls/{n}/files` 是否返回该字段（**实现前必须核实**；若不返回则需要额外请求）

**替代方案**：只加路径规则——选为**首期**（零依赖），`linguist-generated` 作为后续增强。
**数据模型**：`skip_category` 复用 R2 的枚举，新增 `I18N_LOW_PRIORITY` 与 `GENERATED`。
**配置**：`codepilot.review.extra-skip-patterns`（逗号分隔，默认空）让用户追加自定义跳过。
**测试**：各新分类的路径分类断言；`vendor/` 与 `node_modules/` 下的文件被跳过。
**回滚**：配置可覆盖；代码 revert 即可。
**风险**：低。过度跳过会漏审——因此 vendor/node_modules 跳过是安全的，但**i18n 只降优先级不跳过**。

## R12 配置交叉校验 + 路径级审查指令

**问题（两部分）**：
1. `module/tool` 下无配置交叉校验规则（D4）——"配置项新增但未使用"/"使用但未定义"完全查不出
2. **整个项目没有路径级审查指令能力**：对标系统都有（CodeRabbit `.coderabbit.yaml` 的 `path_instructions`、Greptile 的目录级联 `.greptile/`、PR-Agent 的 per-directory `.pr_agent.toml`），我们只有全局 `application.yml`

**方案**：
1. **新确定性规则 `ConfigConsistencyTool`**：解析同一 PR 中变更的配置文件（`*.yml`/`*.properties`/`.env*`）与代码中的配置引用（`@Value("${...}")`、`@ConfigurationProperties`、`environment.getProperty`），报告"新定义未引用"与"新引用未定义"。这是**确定性规则先于 LLM** 架构的自然延伸，与 `SqlRiskTool` 同级。
2. **路径级指令**：新增 `.codepilot/rules.md`（仓库内，随 PR 变更）+ `codepilot.review.path-instructions`（服务端配置），按 glob 匹配文件并把指令注入该文件的 prompt。**安全约束（借鉴 Greptile）**：指令从 **PR 的 base 分支**读取，使"一个 PR 无法通过修改自己的规则文件来放宽对自己的审查"。

**替代方案**：
- 配置校验：用 LLM 语义判断——不选，这正是"确定性规则优先"要避免的
- 路径指令：放数据库由管理员维护——可作为后续，但仓库内文件（随代码 review）更符合"规范即代码"

**数据模型**：路径指令首期放仓库文件，不改库；若要支持服务端覆盖再新增表。
**配置**：`codepilot.review.path-instructions-enabled`（默认 `false`）、`codepilot.tools.config-consistency-enabled`（默认 `true`）。
**测试**：配置校验的正反例（新增未引用 / 引用未定义 / 一致）；路径指令的 glob 匹配；**base 分支读取的断言**（PR 修改 `.codepilot/rules.md` 时本 PR 不生效）。
**回滚**：两个开关分别可关。
**风险**：**中**——路径指令本质是**把用户输入注入 prompt**，是新的 prompt injection 面。缓解：指令走不可信内容包装（`PromptInputSanitizer.escapeUntrustedBlockDelimiters`）、限制长度、从 base 分支读取、默认关闭。

## R13 CI / workflow 变更安全规则

**问题**：`.github/workflows/` 被归为配置(+700)，但**没有任何 CI 专用安全规则**（B17）。`pull_request_target` 注入、权限提升、`secrets` 外泄到 fork PR 都是真实高危。

**方案**：新增确定性规则 `CiWorkflowRiskTool`，在 workflow YAML 变更时检查：
- 使用 `pull_request_target` 的同时 checkout PR head → 高危（经典注入向量）
- `permissions: write-all` 或未显式限制 `permissions`
- 在 `pull_request` 触发的 workflow 中直接引用 `secrets.*`
- `run:` 中拼接 `github.event.*` 用户可控输入（`${{ github.event.pull_request.title }}` 等）
- 新增的第三方 action 未固定到 commit SHA（供应链风险）

**替代方案**：交给 LLM 判断——不选，这些是可枚举的确定性模式，符合"规则优先"；引入 actionlint/zizmor 等外部工具——可作后续增强，但首期自研规则更可控且无新依赖。
**数据模型**：无（复用 `review_issue`，`rule_code` 区分）。
**配置**：`codepilot.tools.ci-workflow-risk-enabled`（默认 `true`）。
**测试**：每个危险模式各一个正例 + 一个安全反例（避免误报）；`pull_request_target` 但未 checkout head → 不报。
**回滚**：开关关闭。
**风险**：**中**——误报会让用户不信任安全规则。缓解：只报可枚举的高置信模式，措辞避免绝对化，并在证据中给出触发片段。

## R14 `pull_request_review_comment` 里的 @mention

**问题**：`GitHubWebhookPayloadParser` 只认 `pull_request`(:18) 与 `issue_comment`(:20)，`pull_request_review_comment` 落入 `ignored("unsupported event")`(:40)。**用户在 diff 行上直接 @bot 没有任何反应**——而这恰恰是最自然的提问位置（就在问题代码旁边）。

**方案**：新增 `pull_request_review_comment` 事件分支：
1. 解析 `comment.body` / `comment.id` / `pull_request.number` / `comment.path` / `comment.line` / `comment.in_reply_to_id`
2. 复用 `GithubCommandParser` 的 mention 解析
3. 去重 key 沿用 comment id（`buildDedupKey` 已有该分支）
4. **上下文增强**：把 `path` + `line` + 该行附近的 diff 上下文注入 Agent 请求，让模型知道"用户在说哪一行"
5. 回复方式：**作为该 thread 的回复**（`in_reply_to_id`），而不是新建顶层评论——这需要为 `GithubClient` 增加回复能力（顺带解决 C8）

**替代方案**：只在 PR 会话流支持——不选，这是对标系统的标配能力，缺失会让"在代码行上提问"这一最自然的交互完全不可用。
**数据模型**：`pr_command_task` 增加 `anchor_path` / `anchor_line` / `reply_to_comment_id`（V24）。
**配置**：`codepilot.github.review-comment-mention-enabled`（默认 `true`，读侧能力）。
**测试**：事件解析；mention 识别；去重；回复发到正确 thread；非 PR 的 review comment 被忽略。
**回滚**：开关关闭即回到 `unsupported event` 行为。
**风险**：低。注意 `pull_request_review_comment` 的 payload 结构与 `issue_comment` 不同（`comment.line` 而非 `issue.number`），需要独立解析测试。

## R15 评论生命周期

**问题**：不检测已发评论是否 outdated（C14）；inline 评论只新建不编辑（C15）；I8 无法精确知道哪几条发出去了。

**方案**：基于 **R7 的 `review_published_comment`** 表：
1. 发布成功即落库 `(task_id, github_comment_id, finding_fingerprint, channel)` → 解决 I8
2. 新一轮审查时，对已落库的 inline 评论判断其 finding 是否已消失：消失且满足 R6 的解决条件 → 通过 GraphQL **resolve 该 thread**（而不是删评论，保留审计痕迹）
3. 摘要评论继续走 marker 更新（不变）

**替代方案**：删除旧评论——不选，破坏审计与讨论历史；编辑旧 inline 评论——不选，position 漂移会导致错位（同 R6 的理由）。
**数据模型**：复用 V23 的表（新增 `thread_resolved_at`）。
**配置**：`codepilot.review.auto-resolve-threads`（默认 **`false`**，属写操作）。
**测试**：发布后落库；finding 消失且条件满足时 resolve；条件不满足时不 resolve；不可能 resolve 时不失败（降级）。
**回滚**：开关关闭。
**风险**：**中**——自动 resolve 人的讨论线程会冒犯用户。缓解：**只 resolve 首条评论是本 bot 的 thread**（与 R7 同一判据），默认关闭。

## R16 修正误导性文案与分类错位

**问题**（三处已核实的不一致）：
1. `ReviewFilePlanner:138` 的 skip_reason 写 `"file type or generated path skipped"`，但**代码中不存在生成物判定**
2. `pnpm-lock.yaml` 因以 `.yaml` 结尾而**不被 `.lock` 命中、因此不被跳过**，却已被 `isDependencyManifestPath` 分类为依赖清单
3. `ReviewIssueLocationGuard:46-55` 置空行号时写 `"line is not a commentable added diff line"`，但 `DiffLineMapper` 实际也接受 LEFT 与 context 行

**方案**：修正文案使其与实现一致；`shouldSkipPath` 补 `pnpm-lock.yaml`（与 R11 合并实施）；把位置判定的 reason 改为准确描述（"line is not commentable on either side of the diff"）。
**替代方案**：保留现状——不选，误导性文案正是 Phase 0 里最容易让人（和面试官）判断错误的来源。
**数据模型**：无（`skip_reason` 文本变化，配合 R2 的 `skip_category` 后其重要性下降）。
**配置**：无。**测试**：断言 reason 文案与 `skip_category` 语义一致；`pnpm-lock.yaml` 被跳过。
**回滚**：纯文本。**风险**：极低。

---

## R17 构建门禁语义

### 问题

**本项目完全没有门禁语义**——而这是 Phase 0 场景矩阵**遗漏的一个场景**（原 142 项里没有"是否阻塞合并"这一条，属于我的枚举缺口，在此补上）。

现状：审查产出只有 GitHub 评论，不提交 review verdict（C10），也不写 commit status/check run。因此：
- 无法与 branch protection / required status checks 联动
- 也没有任何"什么情况下应该阻塞"的定义

对标结论（**这是本节最重要的输入**）：

| 系统 | 默认是否阻塞 | 机制 |
|---|---|---|
| reviewdog | **否** | 退出码默认 `-fail-level=none`，**有 finding 也返回 0**；`level`→状态映射硬编码（info/warning→neutral，error→failure），但要显式设 `-fail-level` 才真正失败 |
| Danger JS | **否** | `-f/--failOnErrors` **默认 false**；门禁主通道是独立 commit status（`context = dangerID`），可用 `--no-publish-check` 关闭 |
| SonarQube | 取决于配置 | **质量门注册为独立 GitHub check（名字 `SonarQube Code Analysis`）**，靠加进 required status checks 卡合并 |
| Semgrep | **是** | `semgrep ci` 有 finding 即退出 1 |

**共同设计原则**：**"产生评论"与"判定失败"是两个独立维度**，且成熟工具默认都不阻塞。

### 方案

1. **不引入 review verdict 作为门禁**（沿用 PR-Agent 的结论：`REQUEST_CHANGES` 风险高、我们已有 Fix 审批链路的保守基调）。改用**独立 commit status**：`context = "CodePilot Review"`，取值 `success` / `neutral` / `failure`。
2. **默认 `neutral`（不阻塞）**。仅在同时满足以下条件时才 `failure`：
   - 开关 `codepilot.review.gate-enabled=true`（**默认 false**）
   - 存在 severity ≥ 阈值的**确定性规则**发现（`codepilot.review.gate-min-severity`，默认 `HIGH`）
   - **LLM 类 finding 默认不参与门禁**（保留 `gate-include-llm-findings`，默认 `false`）——理由：LLM 输出不确定，用它卡合并会让开发者对门禁失去信任
3. **reviewdog 的分级思路**：`level`（展示级别）与 `fail-level`（失败级别）分开配置，互不耦合。

### 替代方案与不选理由

| 方案 | 不选理由 |
|---|---|
| **提交 `REQUEST_CHANGES` review 来卡合并** | PR-Agent 明确不做且把调用点注释掉；GitHub 的 `REQUEST_CHANGES` 会占用"必需 review"语义，与人类 reviewer 冲突；且一旦误判会**阻塞整个 PR** |
| **用"有没有产生评论"当失败条件** | 这正是对标工具刻意避免的耦合（reviewdog 默认 `-fail-level=none`）。有 finding ≠ 该阻塞 |
| **让 LLM finding 参与门禁** | 不确定性太高；eval 只有 6 个样本、Precision 85.71%，用它卡合并是不负责任的 |
| **不做门禁，只发评论** | 也可接受（且是当前状态）。但既然要补这一维度，应做成"默认关、可开、且只在确定性高置信发现上生效" |

### 数据模型与迁移

新增迁移 **V25**：`review_task` 增加 `gate_status VARCHAR(16)`（`SUCCESS`/`NEUTRAL`/`FAILURE`）+ `gate_reason VARCHAR(255)`，用于审计"为什么这次判了 failure"。

### 配置开关与默认值

| 配置 | 默认 | 说明 |
|---|---|---|
| `codepilot.review.on-pass` | **`silent`** | **（对标补充 9）** 通过时的动作：`silent` / `comment` / `status-success`。形态借鉴 Ellipsis 的 `output.on_pass` |
| `codepilot.review.on-fail` | **`comment`** | **（对标补充 9）** 失败时的动作：`comment` / `status-neutral` / `status-failure`。**默认只评论、不写阻塞性状态**。形态借鉴 Ellipsis 的 `output.on_fail` |
| `codepilot.review.gate-min-severity` | `HIGH` | 触发失败判定的最低 severity |
| `codepilot.review.gate-include-llm-findings` | **`false`** | LLM finding 是否参与失败判定 |
| `codepilot.review.gate-context-name` | `CodePilot Review` | commit status 的 context 名（对标 reviewdog / Danger 的 context 命名） |

### 测试计划

- `gate-enabled=false` 时不调用任何 status API
- 只有 MEDIUM 的确定性发现 + 阈值 HIGH → status 为 `neutral`
- 有 HIGH 确定性发现 + 阈值 HIGH → `failure` 且 `gate_reason` 落库
- **有 HIGH 的 LLM finding 但 `gate-include-llm-findings=false` → 仍为 `neutral`**（关键回归测试）
- status API 调用失败不得影响审查任务状态（旁路设计）

### 回滚

`gate-enabled=false`（默认即关）。新增列 nullable。

### 风险

- **中**：误判为 `failure` 会**阻塞他人合并**。缓解：默认关闭 + 只信确定性规则 + severity 阈值可调 + 全量审计（`gate_reason`）。
- **低**：需要在 GitHub App 权限中增加 commit status 写权限，属权限面扩大。缓解：仅在 `gate-enabled=true` 时才需要，文档中明确说明。

---

## R18 LLM finding 的自省闸门

### 问题

我们已有 `ReviewFindingScoreCalculator` 给 finding 打分、`ReviewFindingRanker` 排序，但**没有"自省后再决定要不要"这一道闸门**——LLM 输出的 finding 只要通过 patch verification 与 location guard 就会被发布。因此**未经复核的幻觉 finding 与经过推理的 finding 享受同等待遇**。

### 方案（借鉴 PR-Agent 的 `suggestions_score_threshold` + `score_on_reflection_failure`）

1. 对通过质量门 1/2 的 finding 增加一轮**轻量自省**：让模型只回答"这条 finding 在给定 patch 上是否成立"，输出 0–10 分。
2. 两个关键阈值（PR-Agent 的命名与语义可直接借用）：
   - `suggestions-score-threshold`：低于此分**丢弃**。PR-Agent 官方注释警告**不要高于 8**，否则"may clip highly relevant suggestions"——我们默认 **5**（保守），并在文档写明上调风险。
   - `score-on-reflection-failure`：自省调用失败或输出无法解析时给几分。PR-Agent 取 7（高于其阈值 0，即**失败时保留**）。我们默认取 **0**（**失败即丢弃**）——**这是与 PR-Agent 刻意的分歧**：我们的 eval 只有 6 个样本，宁可漏报也不让未复核内容进 PR。
3. 被丢弃的 finding **不删除**，写 `review_issue` 并标 `publishDecision=SUPPRESS` + `suppressionReason=reflection_below_threshold`，由 R2 在摘要中上报条数（符合 R7 的"可审计抑制"原则）。

### 替代方案与不选理由

| 方案 | 不选理由 |
|---|---|
| **不设闸门，靠 prompt 约束模型自律** | 正是当前现状，也是空泛输出的来源之一 |
| **把自省并进主审查 prompt（一次调用同时出 finding 与分数）** | 省一次调用，但等于让模型"自己给自己打分"，缺乏独立视角 |
| **自省失败时保留**（PR-Agent 取 7 的做法） | 我们评估基础太薄（6 样本 / Precision 85.71%），**失败时保留等于放行未复核内容**。宁可漏报 |
| **用确定性规则替代自省** | 两者互补：确定性规则覆盖可枚举模式，自省覆盖语义判断 |

### 数据模型与迁移

新增迁移 **V26**：`review_issue` 增加 `reflection_score INT` + `reflection_status VARCHAR(16)`（`SCORED` / `FAILED` / `SKIPPED`）。

### 配置开关与默认值

| 配置 | 默认 | 说明 |
|---|---|---|
| `codepilot.review.reflection-enabled` | **`false`** | 是否启用自省闸门。**默认关**（新增一次 LLM 调用 = 成本上升，需先量化收益） |
| `codepilot.review.suggestions-score-threshold` | `5` | 低于此分丢弃。**不要设到 8 以上** |
| `codepilot.review.score-on-reflection-failure` | **`0`** | 自省失败时的分数。**0 = 失败即丢弃**（与 PR-Agent 取 7 相反，理由见上） |
| `codepilot.review.reflection-max-findings` | `10` | 单文件最多自省多少条，控制成本 |

### 测试计划

- 自省给低分 → finding 被抑制且 `suppressionReason` 正确落库
- 自省调用抛异常 → 分数取 `score-on-reflection-failure`；**取 0 时该 finding 不被发布**
- 自省输出无法解析 → 同上，且 `reflection_status=FAILED`
- 阈值边界：等于阈值保留、低于阈值丢弃
- 成本：`reflection-enabled=false` 时不发起任何额外调用

### 回滚

`reflection-enabled=false`（默认即关）。

### 风险

- **中**：自省本身也可能出错，把真 finding 丢掉（漏报）。缓解：默认关闭 + 阈值保守（5）+ 被丢弃的 finding **仍落库并上报**，可通过摘要与数据库复盘误杀率
- **中**：成本上升（每文件多一次调用）。缓解：`reflection-max-findings` 限条数 + 默认关闭 + 先用量化收益（依赖 R10 的 token 指标）

---

## 对标补充：改变前述设计的六条跨项结论

这一节记录在确定性工具组调研完成后，对前述若干条目的**修正**。出处见 [`docs/benchmark-ai-review-tools.md`](benchmark-ai-review-tools.md) 第 3bis 节。

### 补充 1：R6 的指纹必须抗行号漂移——Semgrep 是唯一做到的先例

| 工具 | 指纹是否含行号 | 兜底手段 |
|---|---|---|
| **Semgrep** | **否**（`hash(文件路径 + 规则名 + 元变量代入后的模式)`） | 官方示例证明**在匹配区间内插入无关代码行，指纹不变** |
| reviewdog | 是（`(path, line, fingerprint)`） | 靠**删除过期评论**兜底 |
| Danger JS | 是（按 `File:` + `Line:` 匹配） | 靠**删除匹配不上的旧行内评论**兜底 |
| PR-Agent | **否**（`sha256(file \| line \| normalized_body)` —— 用了 `line` 但注释说明刻意不用 diff position） | 靠状态标记 |

**对 R6 的修正**：指纹**不得包含行号**（我原方案已经是这样，此处补充了业界先例），并且**必须同时具备删除过期评论的能力**（见补充 2）——两者缺一不可，否则行号漂移会导致重复评论。

### 补充 2：R15 需要增加"删除过期评论"，且**有回复的不能删**

reviewdog 的源码里有一个我原方案漏掉的机制：它每轮会 `DeleteComment` 删除**指纹不再出现的自己的评论**，但**通过 `GetInReplyTo` 判断"有回复的除外"**。

**对 R15 的修正**：原方案只做"resolve thread"，现在补上删除路径，判据为：
1. 该评论由本 bot 发出（`review_published_comment` 可查）
2. 其 finding 指纹在本次审查中不再出现
3. **且该评论没有被任何人回复**（`in_reply_to` 无人引用）

**为什么"有回复的不能删"很重要**：一旦有人回复了我们的评论，那条评论就承载了**人类讨论**，删除它等于删掉别人的上下文。这是我在原方案里没有想到的。

**并且**：resolve 与删除是两种不同语义——**resolve 保留痕迹（可审计），删除不留痕迹**。默认应选 **resolve**，删除作为可选开关。

### 补充 3：R7 的抑制必须"可审计"——Semgrep 的语义优于 SonarQube

| 机制 | 行为 | 可审计性 |
|---|---|---|
| **Semgrep `nosemgrep`** | **仍然生成 finding，并自动置为 `Ignored`** | **好** —— 能统计"被忽略了多少、都是什么" |
| SonarQube Accepted / False positive | 服务器端状态，**被排除在质量报告与评级之外** | 中 —— 状态可查但被排除出报告 |
| SonarQube `//NOSONAR` | **静音该行所有现在与未来的问题** | **差** —— 官方自己都在劝阻 |

**对 R7 的修正**：抑制**不得让 finding 从系统中消失**，只能让它不被发布。具体：
- 被抑制的 finding **仍写入 `review_issue`**（带 `publishDecision=SUPPRESS` + `suppressionReason`），只是不发 GitHub 评论
- 摘要中按 R2 上报"被抑制 N 条"
- **禁止**实现"按路径/规则一次性静音"这类不可审计的机制

### 补充 4：R2 的评论预算可采用对标的具体数值

- reviewdog：`maxCommentsPerRequest = 30` / `maxFileComments = 10`，**溢出进 review body 的 `<details>`**
- Semgrep：同一 finding 在 PR 内 **超过 5 次 → 折叠为汇总评论**

**对 R2 的修正**：`coverage-max-listed-files=20` 保留，但**摘要侧的总评论展示上限**建议对齐为 **30 条，其余折叠**（当前 `max-summary-findings=20` 偏小且**没有折叠**，只是截断并说"还有 N 条未展示"）。

### 补充 5：确定性规则应默认只报"本 PR 新增行"

reviewdog 的 `-filter-mode` **默认 `added`**；SonarQube 只报目标分支没有的问题；Semgrep 用 diff-aware scan。**三家默认都是"只看本 PR 改动的行"。**

**对现有实现的评估**：我们的 `DiffLineMapper` 已经做到"只评论 diff 内可评论的行"（`ReviewIssueLocationGuard`），方向一致。但**确定性工具本身扫描的是整个 patch**（`SqlRiskTool` 等接收 `patch`），因此**可能在未改动但出现在 diff context 里的行上产生 finding**。这需要在实现阶段核实并可能收紧。

### 补充 6：fork PR 的写权限降级

reviewdog 明确因 fork PR 的 `GITHUB_TOKEN` 只读而降级到 Actions 注解。

**对本项目的意义**：我们的审查链路不感知 fork（A18），发布侧也没有降级路径。若审查一个 fork PR 且评论发布失败（403），当前行为是 `publish_status=FAILED` → 重试至 `FAILED_FINAL`，**结果完全无法送达用户**。应在实现阶段评估：fork PR 场景下把结果降级为 commit status description 或 Actions 注解，而不是耗尽重试。

> 这一条**尚未纳入任何 R 条目**，因为需要先确认我们实际部署形态（GitHub App vs PAT、是否在 Actions 中运行）下的真实权限行为。**列为待核实项。**

---


## 对标补充 2：AI 原生审查产品带来的修正

出处见 [`docs/benchmark-ai-review-tools.md`](benchmark-ai-review-tools.md) 第 3ter 节。**这一组里有一条推翻了我原先的假设。**

### 补充 7：R2 不只是"补齐"，而是**差异化空间**（假设被推翻）

我原先以为"上报覆盖度"是业界普遍做法。**核实后结论是分裂的**：

| 阵营 | 是否在 PR 里告诉用户"哪些文件没审、为什么" |
|---|---|
| **4 个商用 AI 原生产品** | **一个都没有。** Copilot 只有静态排除清单；Ellipsis 只在 **API** 里暴露 `reviewed_files` / `reviewed_lines` |
| **PR-Agent（开源，源码证实）** | **有**，`enable_review_coverage_footer=true` **默认开** |

**对 R2 的修正**：优先级**不降反升**，因为它同时是（a）对标开源成熟实践（PR-Agent 证明可实现且成本低）和（b）对商用产品的差异点。

**并且 Ellipsis 暴露了一个我没想到的失败模式**：它的策略是"**超限的 reviewer 直接判失败且不贡献任何内容**"。这提示 **R2 需要增加一条判定**：当覆盖度过低（例如审查文件数 / 变更文件数低于阈值）时，**应该把这次审查标记为"覆盖不足"而不是正常成功**——否则用户会把"只审了 5/200 个文件"的摘要当成完整结论。

→ **R2 增加配置**：`codepilot.review.min-coverage-ratio`（默认 `0.5`），低于该比例时摘要顶部加醒目告警，并配合 R17 把 gate 置为 `neutral` 而非 `success`。

### 补充 8：R6 增加一个更简单的替代机制 —— **按 commit 区间记账**

Ellipsis 的去重**不是基于评论文本指纹**，而是**按 commit 区间记账**：每个 reviewer 只看到"它还没评审过的 commit"（`range_base_sha`）；锚定在该区间之外的评论**被扣下不发，但仍可在 API 查到**（`status: outside_range` / `not_in_diff`）。Bugbot 则用 **`git patch-id`** 对同一份 diff 直接短路。

**对 R6 的修正**：指纹方案保留，但增加一个**更低成本的补充层**：
- 在 `review_finding_memory` 上增加 `first_seen_head_sha` / `last_seen_head_sha`（原方案已有），并新增**"该 headSha 的 diff 是否已审过"**的记录（可用 `git patch-id` 等价的 patch 哈希，服务端对 patch 文本做 sha256 即可）
- **若同一 patch 哈希已审过，直接短路跳过 LLM 调用**——这是纯收益（省 token、省时间），且不涉及 finding 语义判断
- 区间外 finding 的处理沿用 Ellipsis 的思路：**不发布，但保留在库中并标记 `outside_range`**，而不是丢弃

**并且这条有一个有力的反面论据支持我们做去重**：**Copilot 官方文档明确承认做不到**——重新审查"may repeat previous comments, **even if you resolved or downvoted them**"。连原生实现都会重复评论，说明这确实是难点，做成就是优势。

### 补充 9：R17 借鉴 Ellipsis 的配置形态

Ellipsis 的结论能力是**两个独立配置**：`output.on_pass` = `silent` | `comment` | `approve`；`output.on_fail` = `comment` | `request_changes`。

**对 R17 的修正**：把 R17 从单一 `gate-enabled` 布尔扩成**两个维度的配置**：
- `codepilot.review.on-pass`：`silent`（默认）| `comment` | `status-success`
- `codepilot.review.on-fail`：`comment`（默认）| `status-neutral` | `status-failure`

这比"一个开关 + 一个阈值"更清晰，也避免了我原方案里"门禁开关同时控制展示与状态"的耦合。

**另注**：Cursor Bugbot **只发一个 check（success/neutral/failure）、完全不发 review verdict**——这与 R17 的核心选择一致，是对"不引入 REQUEST_CHANGES"的独立验证。

### 补充 10：R10 的字段设计可以直接照抄

| 产品 | 暴露的成本/token 字段 |
|---|---|
| **Cursor Bugbot** | analytics API 返回每次审查的 `cost_cents`、findings、`resolution_status`；**并有 dryRun 模式** |
| **Ellipsis** | `cost`（按 llm / cpu / memory / fee 拆分，且**按模型拆分**）+ `tokens`（**含 `cache_read` / `cache_creation`**）+ 硬性 `budget.session` |
| Copilot | 只给估算区间（Lite $0.05–1，Balanced $0.25–5）+ Actions minutes |

**对 R10 的修正**：指标设计应对齐为
- `codepilot.review.cost`（按模型打 tag）
- `codepilot.review.tokens`（按 `prompt` / `completion` / **`cache_read`** / **`cache_creation`** 打 tag）—— **`cache_read` 单独打 tag 很重要**，它是衡量缓存是否真正省钱的唯一依据（我们已有 `ai_review_cache` 但没有任何命中成本数据）
- 新增 **dry-run 模式**（`codepilot.review.publish-dry-run`，默认 `false`）：走完整审查与发布决策链路但**不真正调用 GitHub 写接口**，用于验证 R1/R5/R17 的发布逻辑。这是 Bugbot 已经提供的运维能力。

### 补充 11：R11 的跳过清单可对齐 Copilot

Copilot 的静态排除清单包含：锁文件、**`**/generated/**`**、`**/*.min.js`、**`**/*.d.ts`**、**`**/vendor/**`**、**`**/bin/**`**。

**对 R11 的修正**：我们当前恰好缺粗体的那几类（B14/B15）。**直接采用 Copilot 的清单作为 `shouldSkipPath` 的补充**，比对标里任何一家都全。

**附带发现**：Copilot **曾有 300 文件 / 20,000 行上限，已于 2026-08-27 移除**（转向按 token 预算控制）。这说明"硬性文件数上限"正在被"token 预算 + 明确上报"取代——**再次支持 R2 的方向：与其静默截断，不如按预算选并明确上报。**

### 补充 12：R14 会让我们在这组里领先（但结论要收窄）

**这 4 家里没有任何一家支持"在 diff 行上 @bot"**：Bugbot 与 Ellipsis 只有会话流评论触发（`cursor review` / `@ellipsis`），**Copilot code review 连评论触发都没有**（`@copilot` 驱动的是 cloud agent，不是 review）。

**对 R14 的表述收窄**：可以说"相对这一组是差异点"，**但不能说"业界都不支持"**——CodeRabbit / Greptile 是否支持行级触发**尚未核实**。文档中已按此收窄表述。

---

## 对标补充 3：PR-Agent 的 M6 / M9 / M10 补齐后带来的修正

此前标为"未证实"的三个机制已全部由源码证实（本地 clone，不受网络限制），出处见 [`docs/benchmark-ai-review-tools.md`](benchmark-ai-review-tools.md) 第 2.7 节。

### 补充 13：R10 的字段设计现在有**源码级先例**（不再只是商用产品的 API 观察）

`pr_agent/algo/run_output.py` 已实现一个 opt-in 的 run-details 区块，字段与我们 R10 的设计几乎一一对应：

| PR-Agent 字段 | 位置 | 我们的对应 |
|---|---|---|
| `prompt_tokens` / `completion_tokens` / `total_tokens` | `:150-153` | `codepilot.review.tokens`（按 `prompt`/`completion` 打 tag） |
| **`cache_read_tokens` / `cache_creation_tokens`** | `:156` | **新增**：缓存命中/写入单独计量 |
| `duration_seconds` | `:159` | 已有 `codepilot.review.task.duration` |
| `total_cost_usd` + `cost_status`（`unavailable`/`partial`） | `:162-172` | **新增** `codepilot.review.cost`，并**显式区分"完全不可定价"与"部分可定价"** |
| `model_costs_usd`（多模型按模型拆分） | `:173-175` | **新增**：按 `model` 打 tag |

**一个可直接抄的实现细节**：`_format_usd`（`:125-130`）在"两位小数四舍五入后变成 0 但实际为正"时**显示 `<$0.01` 而不是 `$0.00`**——避免把真实成本显示成零。这类细节决定了成本数据可不可信。

**一个重要澄清（避免照搬错误）**：PR-Agent **没有自己的 LLM 响应缓存**。它的 `cache_read` / `cache_creation` 来自 **provider 侧 prompt caching**（`cache_control_injection_points`），另外只有一个 repo-context 缓存。
→ **我们的 `ai_review_cache` 是应用级响应缓存，与它是完全不同的东西。** 因此不能照搬字段语义，但**"缓存命中必须单独计量"这个原则完全适用**——这正是 R10 里 `codepilot.review.llm.cache`（hit/miss）与补充 10 的 `cache_read` tag 要解决的问题。

### 补充 14：R2 与"跨文件 finding 落点"的关系被澄清

PR-Agent 的 M6 源码显示：**跨文件 / 架构级 finding 的默认归宿是 review 正文**，行内只是 opt-in 的增强（`inline_key_issues`），且**锚定失败的仍留在正文**；无行可锚时降级为 **文件级评论**（`subject_type="file"`）。

**对 R2 的修正**：我们的 `ReviewReportFormatter` 已经承担了"跨文件 finding 的归宿"这一职责（location guard 拒绝后进 Summary），**方向正确**。但缺两层：
1. **opt-in 的行内提升**（能锚定的跨文件 finding 可以考虑挂到最关键的那一行）
2. **文件级降级层**（挂在文件上而不是只能进汇总）

→ 这两点并入 **R4（多行区间）** 的实现范围，不单独开条目。

### 补充 15：新增 R18 —— 自省闸门，且**在一处刻意与 PR-Agent 分歧**

PR-Agent 的 self-reflection 打分通道已证实：`suggestions_score_threshold`（官方警告不要高于 8）+ `score_on_reflection_failure = 7`。

**关键分歧点**：PR-Agent 在自省失败时给 **7 分（高于其阈值，即保留）**；**我们默认给 0 分（丢弃）**。

理由：我们的评估基础是 **6 个样本 / Precision 85.71%**，**薄到不足以支撑"失败时放行"**。PR-Agent 有更大的用户基数与反馈闭环来兜底，我们没有。**这是一个"同样的机制、不同的默认值"的典型取舍——机制可以抄，默认值必须按自己的证据强度定。**

---

# 待决项（本轮不实现）

## D1 增量审查

**现状**：每次 `synchronize` 按新 headSha 建新任务并**全量重审整个 PR diff**。

**为什么挂起**：
1. 对标结论显示**这不是落后**——PR-Agent **默认就是全量重审**，增量是 opt-in（`-i`）且要求能找到上一次 review 评论；只有 CodeRabbit 默认增量
2. PR-Agent 的增量基线是"上一次 review 时间之前的最后一个 commit"，**依赖持久评论身份（R1）与跨评审状态（R6）**——这两项还没做，现在做增量等于建在空中
3. 改变 `synchronize` 语义会影响现有测试与文档承诺，属于**行为契约变更**，应与用户单独确认

**将来实施的取舍**（供决策参考）：

| 方案 | 优点 | 缺点 |
|---|---|---|
| 全量重审 + R6 跨评审去重（**推荐次序**） | 不改变语义；去重收益已经能覆盖大部分噪音；审查始终完整（保证 R6 的 resolve 判据成立） | token 成本仍随 PR 大小增长 |
| 真增量（只审 delta） | 省 token、省时间 | **破坏"完整审查"前提** → 无法安全地判定旧问题已修复；需要维护 per-commit 基线；PR-Agent 的实践说明它复杂度高 |
| 混合：增量审查 + 定期全量 | 兼顾 | 状态机最复杂 |

**建议**：先做 R6（去重），用**指标**量化"全量重审到底浪费了多少"（token/时间/重复 finding 数），再决定是否值得引入增量。**用数据决策，而不是凭直觉。**

## D2 离线评估集扩充

**现状**：`AiReviewPipelineEvalTest` 只要求 `hasSizeGreaterThanOrEqualTo(6)`，当前 **6 个 PR-like 场景**；Precision `85.71%`（= 6/7，在 issueType 级别按 case 拼 key 计算）、Recall `100%`。**这是项目指标可信度的最大短板**——100% recall 建立在 6 个样本上经不起追问。

**用户已确认要做**，但不在本轮（本轮只出设计文档）。

**扩充方案**：
1. 目标 **25–30 个场景**，并按 `scenarioTags` 分组统计，而不只报一个总 precision/recall
2. 覆盖维度（补充现有 6 个之外）：大 PR 预算截断后**未覆盖文件不产生假阴性**、多行 finding、suggestion 可应用性、跨 headSha 重复抑制、误报抑制生效、配置交叉校验、CI workflow 风险、i18n、vendored
3. **新增指标**：per-tag precision/recall、覆盖度上报准确率、重复评论抑制率
4. 在 `docs/ai-review-eval-baseline.md` 中明确写清**口径与局限**（现在是"Non-Goals"写得不错，但缺少"样本量与置信区间"的说明）

---

## 附：本文档与既有文档的关系

| 文档 | 内容 |
|---|---|
| `docs/scenarios.md` | **事实层**：142 场景的现状核实（Phase 0） |
| `docs/benchmark-ai-review-tools.md` | **参考层**：业界 4 个系统的 10 机制对标 |
| `docs/research/*.md` | **原始调研**：PR-Agent 源码深读、CodeRabbit/Greptile/Graphite 官方文档调研 |
| **本文件** | **设计层**：改进项方案与取舍 |
| `docs/code-map.md` | **代码导航**：现有实现的入口到实现映射（实现后需同步更新） |
| `docs/architecture.md` | **架构说明**：现有架构（实现后需同步更新） |
