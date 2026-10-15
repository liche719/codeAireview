# 在真实 PR 上验证 R1 / R2

> **为什么必须做这一步**：R1 和 R2 改的都是**用户在 GitHub 上看到的东西**。
> 单元测试断言的是"调用了 `updateIssueComment(1001)` 而不是 `updateIssueComment(2002)`"，
> 集成测试断言的是"Spring 能启动、迁移能执行"——**它们都证明不了评论渲染出来是对的**。
>
> 本文件是一份可执行的验证流程。**在跑完之前，R1 / R2 只能算"逻辑已验证"，不算"效果已验证"。**

---

## 0. 你会用到的四类评论身份标记

修复后，四类评论各用独立标记。它们是 **HTML 注释**，在 PR 页面上**看不见**——必须用下面的方法读取原始正文。

| 用途 | 标记 | 是否可被覆盖 |
|---|---|---|
| 审查摘要 | `<!-- codepilot-ai-review:liche719/codeAireview -->`（可用 `CODEPILOT_GITHUB_COMMENT_MARKER` 覆盖） | **是**（每次审查更新同一条） |
| Agent 回复 | `<!-- codepilot-agent-reply -->` | 否（只追加） |
| 命令提示 | `<!-- codepilot-command-notice -->` | 否（只追加） |
| 修复结果 | `<!-- codepilot-fix-result -->` | 否（只追加） |

**读取原始正文的方法**（`gh` CLI 最方便）：

```bash
gh api "repos/$OWNER/$REPO/issues/$PR_NUMBER/comments" \
  --jq '.[] | "\(.id)\t\(.user.login)\t\(.body | split("\n")[0])"'
```

最后会输出每一条评论的 id、作者、以及**第一行**——第一行就是身份标记。
逐条对照：应当**每种标记最多出现一次**（Agent 回复可能多次，因为是多轮对话）。

---

## 1. 准备

### 1.0 前置：JVM 能否访问 GitHub API

**先做这一步**，否则后面全部白费。很多国内环境装了网络加速器（Watt Toolkit / 原 Steam++ 等），
它在本机 443 端口对 `github.com` / `api.github.com` 做 **TLS 中间人**转发。

- **浏览器能开 GitHub、PowerShell 也能调到 API** → 因为它们信任 Windows 证书库
- **JVM 会失败**：`SSLHandshakeException: unable to find valid certification path to requested target`
  —— Java 只信任自己的 `cacerts`，**不读 Windows 证书库**

验证方法（用应用实际的客户端，不要用浏览器）：

```powershell
# 直接跑一个 Java 探测；若 GitHub 那行报 SSLHandshakeException，就是这个原因
java -e 2>$null; Write-Host "见下方脚本说明"
```

最简判断：启动应用后调用一次 `POST /api/reviews`，日志里若出现
`SSLHandshakeException`，即为此问题。

**修法**——生成一份**专用** truststore（不改动 JDK 全局 `cacerts`）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/build-local-truststore.ps1
```

启动应用时指向它：

```
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Djavax.net.ssl.trustStore=target/codepilot-truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit"
```

> ⚠️ 这等于**在本次运行中信任一个中间人 CA**。仅限本地调试，不要用于生产。
> 脚本落在 `target/` 下（已被 gitignore）；`mvn clean` 后需重新生成。
> 加速器升级或证书轮换后也要重新生成。

**LLM 侧**：`api.openai.com` 在国内常常直接不可达。项目是 OpenAI-compatible 的，
换成可达的 provider 即可，例如：

```env
CODEPILOT_LLM_BASE_URL=https://api.deepseek.com/v1
CODEPILOT_LLM_API_KEY=sk-xxx
CODEPILOT_EMBEDDING_BASE_URL=https://api.siliconflow.cn/v1
CODEPILOT_EMBEDDING_API_KEY=sk-xxx
# 若 embedding 暂不可用，可先关掉 RAG 以便跑通其余链路
CODEPILOT_RAG_ENABLED=false
```

### 1.1 环境变量

```env
CODEPILOT_API_AUTH_API_KEY=local-dev-key
CODEPILOT_DB_URL=jdbc:postgresql://localhost:15432/codepilot
CODEPILOT_DB_USERNAME=codepilot
CODEPILOT_DB_PASSWORD=codepilot123
CODEPILOT_REDIS_HOST=localhost
CODEPILOT_RABBITMQ_HOST=localhost

# 真实外呼所需
CODEPILOT_GITHUB_TOKEN=ghp_xxx
CODEPILOT_LLM_API_KEY=sk-xxx
CODEPILOT_EMBEDDING_API_KEY=sk-xxx

# 打开写回（默认是关的）
CODEPILOT_GITHUB_COMMENT_ENABLED=true
CODEPILOT_GITHUB_INLINE_COMMENT_ENABLED=true
CODEPILOT_GITHUB_ALLOWED_REPOSITORIES=<你的测试仓库>

# Webhook（如果走 webhook 触发）
CODEPILOT_GITHUB_WEBHOOK_ENABLED=true
CODEPILOT_GITHUB_WEBHOOK_SECRET=<随便一个强随机串>
```

**PAT 需要的权限**：`repo`（读写 issue/PR 评论与文件）、`read:user`（`getAuthenticatedUserLogin` 要调用 `/user`）。
后者尤其重要——R1 的作者过滤依赖它。**如果 token 缺少 `read:user`，作者过滤会降级为只按标记匹配**（不会报错，但少了一层保护）。

### 1.2 准备测试 PR

建一个测试仓库，提一个 PR，改动**至少 3 个文件**，其中：
- 一个正常 Java 文件
- 一个 `.md` 或锁文件类（会被跳过的路径）
- 一个能触发规则的改动（例如拼接 SQL 或硬编码密钥）

---

## 2. 验证 R1：Agent 回复与摘要互不覆盖

### 2.1 操作顺序（顺序很关键）

```bash
# ① 先让审查跑一次，产生摘要评论
gh api "repos/$OWNER/$REPO/issues/$PR_NUMBER/comments" -f body='/review'

# 等审查完成（观察日志出现 "Updated CodePilot GitHub PR comment" 或 "Created ..."）

# ② 再让 Agent 回复一次
gh api "repos/$OWNER/$REPO/issues/$PR_NUMBER/comments" \
  -f body='@x-pilotx 用一句话总结这个 PR'

# 等 Agent 完成
```

### 2.2 关键步骤：让摘要**再更新一次**

这是暴露缺陷的必要动作——**修复前，摘要会覆盖掉 Agent 回复**。

最简单的方式是让同一个 PR 有一次新的 push（触发 `synchronize`），或者手动再触发一次审查：

```bash
gh api "repos/$OWNER/$REPO/issues/$PR_NUMBER/comments" -f body='/review'
```

> 注意：手动 `/review` 与已存在的任务在 `repo/pr/headSha/commentMode` 上会命中复用，
> 可能直接返回已存在的任务而不重发评论。**要确保摘要真的更新了一次**，最可靠的是往 PR 推一个新 commit。

### 2.3 判定标准

重新列出评论：

```bash
gh api "repos/$OWNER/$REPO/issues/$PR_NUMBER/comments" \
  --jq '.[] | "\(.id)\t\(.body | split("\n")[0])\t\(.body | length)"'
```

**通过**：
- 存在一条以 `<!-- codepilot-ai-review:... -->` 开头的评论（摘要）
- **同时**存在一条以 `<!-- codepilot-agent-reply -->` 开头的评论（Agent 回复）
- 两条的内容都是**各自的**：摘要里是问题列表，Agent 回复里是那个总结
- Agent 回复的正文**没有被替换成摘要内容**

**失败（修复前会看到）**：
- 只有一条评论，且内容变成了摘要 → **Agent 回复被覆盖了**
- 或者 Agent 回复的正文里出现了审查问题列表

### 2.4 补充验证：自定义 marker 也应生效

如果你设置了 `CODEPILOT_GITHUB_COMMENT_MARKER=<!-- my-marker -->`，摘要应使用该标记；
`CommentMarkers.REVIEW_SUMMARY` 的默认值不应出现。同时 webhook 入口应把带 `my-marker` 的评论
识别为机器人评论（日志出现 `ignored ... bot response`），否则机器人会回复自己形成循环。

---

## 3. 验证 R2：覆盖度上报 + 无假「未发现问题」

### 3.1 场景 A：部分文件被跳过（最常见）

用一个**改动超过 `CODEPILOT_REVIEW_MAX_FILES_PER_TASK`（默认 30）个文件**的 PR，
或把该值临时调小（例如 `=1`）以便复现。

```bash
# 临时把预算调到 1，方便复现
CODEPILOT_REVIEW_MAX_FILES_PER_TASK=1
```

触发审查后看摘要正文：

**通过**——摘要中应出现：

```markdown
> ⚠️ **审查覆盖不足**：仅审查了 1 / N 个变更文件（X%）。未审查部分的问题不会被发现，请谨慎依赖本次结论。
```

以及末尾的：

```markdown
### 审查覆盖

- 本次审查 **1 / N** 个变更文件（跳过 N-1 个）

未审查文件（按原因分组）：

- **预算分配给了更高优先级的文件**（N-1 个）：
  - `src/...`
```

**失败**——摘要里**完全没有**「审查覆盖」字样，或只列问题不提跳过。

### 3.2 场景 B：**全部**文件被跳过（S2 的核心）

把预算或单文件上限调到必然跳过全部文件：

```env
CODEPILOT_REVIEW_MAX_PATCH_CHARS_PER_FILE=1
```

**通过**——摘要必须**同时**满足：

1. 顶部出现 `> ⚠️ **未能审查任何变更文件**（共 N 个）。本次结论**不代表 PR 没有问题**`
2. 问题列表位置是「本次没有可报告的问题。」+「但**有 N 个变更文件未被审查**」
3. **正文中绝对不出现「未发现问题」这五个字**

```bash
# 直接断言最后一条摘要评论里没有这句话
gh api "repos/$OWNER/$REPO/issues/$PR_NUMBER/comments" \
  --jq '.[] | select(.body | startswith("<!-- codepilot-ai-review")) | .body' \
  | grep -c '未发现问题'
# 期望输出：0
```

**失败**——出现「未发现问题。」→ **S2 没有修好**，这是一个假体检合格。

### 3.3 场景 C：覆盖度区块被关闭时

```env
CODEPILOT_REVIEW_COVERAGE_REPORT_ENABLED=false
```

**通过**：不再出现 `### 审查覆盖` 区块，**但**「未能审查任何变更文件」的告警**依然出现**，
且**依然不出现「未发现问题」**。

> 这条验证的是"安全约束不受开关影响"这个设计承诺。

---

## 4. 归档验证结果

把实际看到的摘要正文存下来，附上 PR 链接。仓库里已有类似的证据基线：

- `target/codepilot-baseline/review-reliability-baseline.json`（由 `scripts/run-review-reliability-baseline.ps1` 生成）
- `docs/github-sandbox-e2e.md`（现有沙箱 E2E 流程）

建议照同样风格新增一份 `docs/evidence/` 记录，包含：PR 链接、head sha、配置、**摘要评论原文**、
以及上面每条断言的实际输出。

---

## 5. 当前状态（诚实声明）

| 项 | 状态 |
|---|---|
| R1 / R2 逻辑验证 | ✅ **已完成** —— 562 个测试，含 4 个真实 PostgreSQL/pgvector + Redis + RabbitMQ 集成测试 |
| R1 / R2 **效果**验证 | ❌ **未做** —— 本机没有任何 GitHub / LLM 凭据（`.env` 不存在，环境变量未设置），无法调用真实 GitHub |
| 自动化 E2E | ⬜ 现有 `GitHubSandboxE2ETest`（opt-in）覆盖 webhook 发布与 linked issue，**尚未覆盖 R1/R2 的这两个断言** |

### 建议的后续

1. **拿到凭据后按本文跑一遍**，把结果归档。
2. 把本文第 2.3 与第 3.2 节的断言**固化成 `GitHubSandboxE2ETest` 的新测试方法**——
   这样以后每次改动都能自动回归，而不是靠人肉核对。这是本文件最重要的一条后续动作。
