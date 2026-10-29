# 企业级改造路线（2026-10-29，按用户确认的口径）

## 已确认的前提（来自用户选择）

| 维度 | 选定 |
|---|---|
| 部署形态 | **单企业私有化部署**，不做多租户 |
| 模型与数据 | 代码**可以**发给公有云 LLM（现状 DeepSeek），只需在文档里写清 |
| 规模目标 | 单组织几十个仓库，日增几十个 PR |
| 管理端 | **只做 API + 文档**，不做前端 |
| 交付定位 | **简历级**：把企业级的关键机制做出来并演示，不追求完整运维 |

> 这四条决定了取舍：不做租户隔离与计费体系，不做前端，不做水平扩展压测；
> 但**门禁、配置、审计、告警、成本**这五件必须真做，因为企业采购/面试都会问。

---

## 五项能力与验收标准

### ① 仓库级配置 `.codepilot.yml`

每个仓库自己定策略，平台不再靠改部署配置来适配不同团队。

**验收**：
- 在仓库根目录放 `.codepilot.yml`，审查时自动拉取并生效；
- 至少支持：`review.enabled`、`review.ignore_paths`（glob）、`review.severity_threshold`、
  `review.inline_comment_max`、`review.comment_mode`、`rules[]`（按路径追加审查指令）；
- 配置写错时**不阻断审查**，退回默认策略并记一条审计（企业最怕"配置错导致全挂"）；
- 单测覆盖：解析、glob 匹配、阈值过滤、坏配置降级。

### ② 强制门禁 GitHub Check Run

把 AI 审查变成**合并前的硬门禁**，而不是一条评论。

**验收**：
- 审查完成后在 PR head 上创建 check run（名称 `CodePilot AI Review`）；
- 高危（阈值可配）→ `failure`，可被 branch protection 设为 required；
- 无高危 → `success`；审查本身失败 → `neutral`（**不能因为我们的故障卡住人家合并**）；
- check run 的 summary 带覆盖度与 finding 计数，details 指向摘要评论；
- 单测覆盖：结论映射、review 失败时的 neutral 兜底。

### ③ 审计与权限

谁能触发、谁批准了修复、谁改了配置——出问题要能查。

**验收**：
- `audit_log` 表：`action`（TRIGGER_REVIEW / APPROVE_FIX / UPDATE_CONFIG / …）、操作者、来源 IP、目标、结果、时间；
- 触发审查、审批修复、配置变更三处必须落审计；
- API Key 支持**按用途分权**（读/触发/审批），至少两个 key 并存；
- 查询接口：`GET /api/audit?days=7&action=…`。

### ④ SLO 与告警

**验收**：
- 定义三个 SLO：审查成功率 ≥ 95%、p95 耗时 ≤ 60s、积压（最老 PENDING）≤ 5min；
- `/api/stats/review` 增加 `slo` 段落，给出当前值与达标状态；
- 可配置告警：连续 N 次不达标 → 输出到日志 + 可选 webhook（不引入 Prometheus）；
- 文档写清"我们的 SLO 是多少、超了会怎样"。

### ⑤ 配额与成本

**验收**：
- 按仓库/按天统计 token 与估算费用（现有 `codepilot.review.llm.tokens` 只有累计值）；
- `/api/stats/cost?days=7` 给出按天、按仓库的分布；
- 支持**日预算上限**：超了就对新任务打标（`budget_exceeded`）并拒绝或降级为只跑确定性规则；
- 冷启动/限流可配。

---

## 顺序与理由

1. **① 配置**——其他四项都要读它（阈值、开关），是地基；
2. **② 门禁**——企业最认的产出，且能立刻在真实 PR 上演示；
3. **③ 审计**——配置变更与门禁结论都需要"谁干的"这条记录；
4. **④ SLO**——已有指标接口，加阈值与告警即可；
5. **⑤ 成本**——依赖 ③ 的归属信息（谁/哪个仓库花的钱）。

## 明确不做（这次）

- 多租户与计费（用户选了私有化）；
- 管理前端（只做 API）；
- 水平扩展压测（规模不需要）；
- 增量审查（收益低于上面五项，且成本高）；
- 私有化模型端点（用户确认可发公有云）。
---

## 进度记录

### 2026-10-29：Agent 编排可观测性（对应路线 A）——修了两个真问题

用 `@x-pilotx` 在沙箱 PR 上真实触发 agent，从 `agent_run` / `agent_step` 取轨迹，发现两个缺陷：

**① `agent_run.tool_call_count` 恒为 0**

轨迹里有清楚的两次工具调用：

```
run 2: step1 MODEL → step2 TOOL searchTeamRules(FAILED) → step4 TOOL getStoredReviewFindings(SUCCESS)
       而 agent_run.tool_call_count = 0
```

根因：运行时手里那份 `AgentRun` 是**调工具之前**加载的，收尾 `updateById(run)` 把整行写回，
顺手把计数覆盖成 0。后果不只是指标难看——**限额 `max-tool-calls` 就是靠这个计数判断的**，
覆盖成 0 等于限额永远不触发。

修法：`complete()` / `fail()` 收尾前先 `selectById` 刷新计数（`AgentRunCounterRefreshTest` 2 条用例锁住）。
验证：修复后 run 5 调了 1 次工具，`tool_call_count = 1`。

**② `searchTeamRules` 在部署里直接失败**

```
step3 TOOL_RESULT searchTeamRules FAILED — BusinessException: embedding is disabled
```

部署没配 embedding key，于是"查团队规范"这个能力**在线上完全不可用**，模型只能回"查不到"。
企业部署里 embedding 往往是最后才接的，这期间不该让整条 RAG 作废。

修法：语义检索失败时降级为 `ILIKE` 关键词检索（`RuleChunkMapper.searchByKeyword`），并打 WARN 记录降级原因。
验证：修复后 run 5 的 `searchTeamRules` 返回 **SUCCESS**。

**这两条的价值**：不是"又加了功能"，而是把"Agent 的轨迹数据可不可信"这件事做实——
轨迹是后面算成本、做限额、排查问题的基础，它一开始就是错的，后面全是错的。
### 2026-10-29（续）：限额治理 —— 一个修好了，一个**没做成**

**修好并验证的：`tool_call_count` 与 `searchTeamRules`**（见上一节），
修复后真实 agent run 里 `tool_call_count = 1`、`searchTeamRules` 返回 `SUCCESS`。

**没做成的：`max-model-turns` 限额仍然不生效。**

- 现象：把 `CODEPILOT_AGENT_MAX_MODEL_TURNS` 设为 1 后触发一次需要工具的问答，
  运行仍然 `COMPLETED`，`model_turn_count` 恒为 **1**——限额没有触发；
- 根因：`beginModelTurn` 只在任务开始时调用一次，之后 LangChain4j 的 AiServices
  在内部跑完整条工具循环，**中间的模型调用没有被计数**；
- 我尝试的修法：加 `ChatModelListener`，在每次真实模型请求前调用 `recordModelTurn(runId)`，
  并把 listener 挂到 `codeReviewChatModel` 上；
- **结果：仍然 `model_turn_count = 1`，说明 listener 没有真正生效**（或 AiServices 的调用
  没走这个监听器）。已回滚 `max-model-turns` 配置到 8，服务恢复健康（0 ERROR）。

**为什么如实记下来**：这条限额现在的状态是"配置看起来有、实际不生效"，
比"没有这个功能"更危险——它会让运维以为有护栏。要么把计数做成真的，
要么把这条限额从文档和配置里删掉，**不能留着装样子**。

下一步（未完成）：
1. 确认 `ChatModelListener` 是否被 AiServices 的 tool loop 触发（打日志验证）；
2. 如果 listener 路线不通，改成在**工具调用边界**校验：`recordToolCall` 时检查
   `model_turn_count` 与工具调用次数是否超阈值——那里是我们自己的代码，一定能拦住。
