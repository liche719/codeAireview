# 真实模型评测基线（2026-10-29）

> 这份文档要解决的问题：现有的 `AiReviewPipelineEvalTest` 用 `FakeReviewLlmClient` **回放预设输出**，
> 所以 `precision = 1.0 / recall = 1.0` 证明的是「流水线与闸门正确」，**不是「模型审得好」**。
> 本文件记录**真实模型**（deepseek-chat）在同一批用例上的表现，两者必须分开看。

## 怎么跑

```bash
python scripts/eval-real-llm.py                 # 全部 26 个用例
python scripts/eval-real-llm.py --limit 6       # 先跑前 6 个
python scripts/eval-real-llm.py --out report.json
```

脚本读 `.env` 的 `CODEPILOT_LLM_BASE_URL / CODEPILOT_LLM_API_KEY / CODEPILOT_LLM_MODEL`，
用**项目自己的 prompt 模板**（`src/main/resources/prompts/ai-review-*.txt`）逐文件调用模型，
并模拟生产的 location guard：**只有落在 diff 覆盖行上的 finding 才算有效**。

## 基线对比

| 指标 | mock 评测（CI 里那个） | 真实模型·改前 | 真实模型·改后 |
|---|---|---|---|
| 召回率 recall | 100%（17/17 标注项全中） | **63.0%（17/27）** | **70.4%（19/27）** |
| must-not 违规 | 0 | **4** | **2** |
| clean 场景误报 | 0 | **13 条** | **6 条** |

改动内容：在系统提示里加了两节——「已知的高频误报（不要报）」和「容易漏报（必须报）」，
依据是改前那轮实测出来的具体误报项（环境变量读密钥被当泄露、只改测试文件仍报缺测试、
固定版本的 workflow 被报风险、报 patch 之外的行号）。

## 剩下的 3 条"漏报"不是模型的锅

| 用例 | 为什么脚本里必然不命中 |
|---|---|
| `invalid-llm-response-parse-failure` | 它验证的是「模型返回坏 JSON 时被记为 AI_REVIEW_FAILED」，真实模型返回的是合法 JSON |
| `malformed-llm-json-keeps-other-file-findings` | 同上，属于 parse 分支的闸门行为 |
| `llm-finding-on-deleted-line-is-commentable` | 删除行的可评论性由 location guard 决定，本脚本只模拟了"行是否在 patch 内" |

**口径**：真实模型的有效召回按非闸门用例算是 **19/24 ≈ 79%**；写简历时不要报 100%，
也不要把闸门用例的 miss 算成模型能力问题。

## 还剩 2 条真实误报

1. `no-test-missing-when-test-file-changed`：只改了测试文件，模型仍报「新增公开方法 charge() 缺少测试覆盖」；
2. `llm-hallucinated-line-outside-patch`：报了「类体中出现游离语句，无法通过编译」，该判断在 diff 证据上站不住。

## 待办

- [ ] 用真实 PR（而非合成 diff）扩充样本到 15~20 个，人工标注缺陷，重算 precision/recall 与置信区间；
- [ ] 把闸门分支（parse failure / deleted line）在脚本里完整模拟，让 24 → 27 个用例都能被真实模型评测；
- [ ] 针对上面 2 条误报继续调 prompt 或交给确定性规则处理。
## 第二轮改进（同日）

1. **prompt**：补「同一 PR 里已经加了测试就不要再报 TEST_MISSING」；
2. **用例**：`llm-hallucinated-line-outside-patch` 的 `mustNotFindings` 原来写成"任何 BUG_RISK 都不许报"，
   范围过宽（diff 里那行确实引用了未定义变量，报编译风险是合理的），收窄为"不许引用 patch 之外的行"；
3. **脚本**：闸门类用例（坏 JSON / 删除行语义）显式跳过并单独报数，另记录单文件审查耗时。

| 指标 | mock | 真实·第一轮 | 真实·第二轮 |
|---|---|---|---|
| recall（含闸门用例） | 100% | 63.0% | — |
| recall（**跳过闸门用例**） | — | — | **79.2% (19/24)** |
| must-not 违规 | 0 | 4 | **1** |
| clean 场景误报 | 0 | 13 | **6** |
| 单文件审查耗时 | — | — | **p50 3.5s / p90 4.6s** |

剩下的 1 条违规是 `no-test-missing-when-test-file-changed`：PR 同时改了生产类和测试类，
模型仍报「charge() 缺少测试」。prompt 已经写了两遍仍不稳定——
**这说明它应该交给确定性规则**（PR 里已有同名测试文件时丢弃 LLM 的 TEST_MISSING），而不是继续堆 prompt。
## 第三轮：把"模型判断不稳定"的那条交给确定性规则

第二轮剩下的唯一违规是「PR 里已经补了测试，模型还报 TEST_MISSING」。**prompt 写了两遍仍不稳定**，
于是改成规则：新增 `ReviewTestCoverageGuard`——本次 PR 的变更文件里存在与目标类同名的测试
（`Xxx` → `XxxTest`）时，丢弃 **LLM 报的** `TEST_MISSING`；确定性工具报的一律不碰。

另外补了两条 prompt 澄清（都在真实评测里对应到具体误报）：
- diff 是**片段**，方法签名/字段可能不在 diff 里 —— 不要因为片段不完整就报"无法通过编译"；
- 只读查询把 `DataAccessException` 交给框架，**不要只因为没写 try-catch 就报异常处理问题**。

| 指标 | mock | 第一轮 | 第二轮 | 第三轮 |
|---|---|---|---|---|
| recall（跳过闸门用例） | — | 63.0% | 79.2% | **79.2%** |
| must-not 违规 | 0 | 4 | 1 | **0** |
| clean 场景误报 | 0 | 13 | 6 | **4** |
| 单文件审查耗时 | — | — | p50 3.5s | **p50 3.2s / p90 4.4s** |

**回归**：`AiReviewPipelineEvalTest`（mock 基线）与 `ReviewTestCoverageGuardTest`（5 条新用例）
全部通过——规则没有破坏原有闸门行为。

**口径**：79.2% 是"跳过 3 个闸门用例"后的真实召回；不要写成 100%，也不要把闸门用例的 miss 算到模型头上。

## ④ 上线：运营指标接口已在生产可查（2026-10-29）

`GET /api/stats/review?days=30` 实测返回（生产库真实数据）：

```json
{"tasks": {"total": 11, "byStatus": {"SUCCESS": 10, "FAILED": 1}, "successRate": 0.909},
 "duration": {"samples": 11, "p50Seconds": 15.1, "p90Seconds": 19.0, "maxSeconds": 21.4},
 "failures": {"total": 1, "byType": [{"type": "github-auth", "count": 1}]},
 "publish": {"taskLevel": {"PUBLISHED": 10, "NOT_REQUESTED": 1},
             "issueLevel": {"PUBLISH": 100, "SUPPRESS": 112}, "issuePublishRate": 0.472},
 "backlog": {"pendingTasks": 0, "oldestWaitSeconds": 0}}
```

**这组数字本身就是这条接口的价值**：一眼看出"唯一的失败是 GitHub 鉴权过期"、
"约一半的问题被闸门抑制（112/212）"、"积压为 0"、"任务耗时 p50 15 秒"。
在没有 Prometheus 的部署里，这些以前要靠翻日志。
## 真实提交上的表现（2026-10-29，比合成用例更难）

脚本：`scripts/eval-real-prs.py` —— 取本仓库**真实历史提交**的 diff，用同一套 prompt 逐文件调真实模型。
和合成用例的区别很关键：合成用例里的"正确答案"是我们自己埋的缺陷；真实提交里的代码**本来就通过了 CI 和 code review**，
模型报出来的每一条都要人判断"站不站得住"。

跑法：

```bash
python scripts/eval-real-prs.py --commits 6 --limit-files 2 --out real-prs.json
```

### 结果：6 个文件，模型报 15 条，人工复核 **8 条成立**

| 判定 | 条数 | 例子 |
|---|---|---|
| 成立 | **8** | 「`failureTypes` 把整列 `error_message` 拉进内存再归类，应该用 `GROUP BY`」<br>「`contains("401")` 这种宽泛匹配容易误分类」<br>「统计失败被 catch 吞掉只打一行 warn，返回结果缺字段」 |
| 误报 | **7** | 「`days` 未做范围校验」——service 里 `Math.max(1, Math.min(90, days))` 已经 clamp<br>「`queryForMap` 可能返回 null」——它没有行会抛异常，不会返回 null<br>「新增方法缺少单元测试」——测试就在同一个提交里 |

**成立的 8 条里没有一条是"会导致线上事故"的**，多数是健壮性/可维护性——这符合预期：
已经合并的代码通常没有硬 bug，模型擅长挑出来的是"可以更好"的地方。

### 这个数字怎么用

- **不要**写成"准确率 53%"：这些提交不是"标准答案"，人工判定也是我一个人的判断；
- **可以**写成"在 6 个真实文件上，模型报出的 15 条里有 8 条经人工复核成立"；
- 面试被追问时，这个口径比"precision 100%"可信得多，而且能顺势讲清"合成用例 vs 真实代码"的差别。

### 暴露出的可改进点

误报集中在一类：**模型看不到被调用方**。
`days` 的 clamp 在 service 里、测试在同一个提交的另一个文件里——模型只看当前文件的 diff，
就断言"缺少校验 / 缺少测试"。这和前面那条"diff 是片段"是同一个根因，
只是这次跨的是**文件边界**而不是行边界。下一步可以：把同一次审查里其它文件的摘要（文件名 + 公开方法签名）
一起给模型，让它知道"这个 PR 里已经有测试文件了"。
