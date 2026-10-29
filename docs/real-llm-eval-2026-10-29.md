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
