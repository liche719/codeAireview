# PR-Agent / Qodo Merge 源码深度调研

> 调研对象：`qodo-ai/pr-agent`（原 `Codium-ai/pr-agent`）
> 源码版本：本地完整 clone，commit `330f0813b5fe0d1f54b794077c2d02aa85a1fb63`（`origin = https://github.com/qodo-ai/pr-agent.git`）
> 调研方式：直接阅读源码（非 README）。路径均相对仓库根目录。
> 诚实声明：本报告中的每个结论都标注了来源与置信度。**未亲读的文件一律写「未证实」，不做推测性补全。** 具体地，以下部分因调研配额中断未能读取，相关结论已明确标注：`servers/github_app.py` 等 server 层的命令分发与重复投递防护、`algo/run_details.py` / `algo/run_output.py` / `telemetry/` 的成本与指标细节、`tools/pr_code_suggestions.py` 的 self-reflection 打分内部实现。

---

## 系统架构总览（一段话）

PR-Agent 是一个**以「PR 事件 → 工具分发 → LLM → GitHub 评论」为主干**的多工具 LLM 代理：入口层（`servers/github_app.py` 的 webhook app、`servers/github_polling.py` 的轮询 app、`servers/github_action_runner.py` 的 GitHub Action、以及 CLI）把一次触发解析成「PR URL + 命令名 + 参数」，实例化对应的工具类；每个工具类（`tools/pr_reviewer.py`、`tools/pr_code_suggestions.py`、`tools/pr_description.py` 等）都遵循同一套骨架：**先从 GitProvider 抽象层（`git_providers/git_provider.py` + `github_provider.py` 等实现）拉取 diff 与文件内容，再经 `algo/pr_processing.py` 组装成受 token 预算约束的 prompt，通过 `LiteLLMAIHandler` 调用 LLM 拿到 YAML，解析校验后渲染成 Markdown，最后交给 provider 发布**；发布环节被刻意收敛为「评论」而非「review verdict」——正文走 issue comment（`publish_comment`），内联意见走 `create_review(event="COMMENT")`，并用**写在评论正文里的隐藏 HTML 标记**（身份标记 + 版本化 finding 状态 JSON + inline 指纹）+ **编辑同一条持久评论**来实现跨运行的幂等与状态记忆，不依赖任何外部数据库。LLM 层之上还有一层重试/降级（`retry_with_fallback_models`，主模型失败自动换 `fallback_models`）和一层 token 预算/裁剪（`AttemptTokenBudget`、`pr_generate_compressed_diff`、`get_pr_multi_diffs`），这套「预算 → 压缩 → 分块 → 上报未覆盖文件」的链路是本系统最值得借鉴的工程部分。

---

## 1. 跨 push 的重复评论避免

**答案**：三层独立机制。(a) **编辑同一条持久评论**（默认开）：用隐藏身份标记 `<!-- pr-agent:review:full -->` 找回上次那条 review 评论并 `edit_comment` 覆盖，而不是新发一条。(b) **持久 finding 状态**：把一份带 `schema_version` 的 JSON（每条 finding 的指纹、`ACTIVE/RESOLVED`、`reopened_count`、以及 `last_run.head_sha`）作为隐藏标记塞在 review 评论正文里，下次运行解析出来做对账（reconcile），并把「还活着的」finding 以 JSON 注回 prompt，让模型沿用原措辞而不是换个说法重报。(c) **inline 评论指纹去重（opt-in，默认关）**：对每条内联评论算 sha256[:12] 指纹（正文指纹 + suggestion 代码指纹，OR 语义），指纹以 HTML 标记写进已发布评论正文；下次运行扫描该 PR 所有已有评论正文重建「已发过」集合，命中即跳过。**没有**独立的「已审查文件集合」持久化——「哪些 commit 已审查过」是通过增量模式的基线 commit SHA 隐式表达的（见第 2 题）。

**证据**：
- `algo/review_finding_state.py:1` 模块 docstring：`"""Persist review finding state across runs."""`；`:15-20` 定义状态标记正则与命名空间：
  ```python
  STATE_SCHEMA_VERSION = 1
  _STATE_MARKER_RE = re.compile(
      r"<!-- pr-agent-review-state:v(?P<version>\d+)\n(?P<payload>.*?)\n-->", re.DOTALL)
  ```
- `algo/review_finding_state.py:199-232` `reconcile_review_findings()` 的对账与「允许标记为已修」的守卫：
  ```python
  resolution_allowed = (
      allow_resolution
      and ... bool(previous_head_sha.strip()) and bool(head_sha.strip())
      and previous_head_sha != head_sha
  )
  ```
- `algo/review_finding_state.py:262-275`：上次 ACTIVE、本次消失且 `resolution_allowed` → 置 `RESOLVED` 并记 `resolved_head_sha` / `resolution_run_id`。
- `algo/inline_comment_dedup.py:1-31` docstring 明说是 issue #2037（同一 PR 多跑几次会重复贴同样的 inline suggestion），并说明「OR 语义双指纹」是为了同时覆盖「同文不同码」与「同码不同文」两种 LLM 复述方式。
- `algo/inline_comment_dedup.py:96-134`：`_body_fingerprint` 先剥 `**Suggestion:**` 前缀（`_LEAD_RE`）与 `[..., importance: N]` 标签（`_TAG_RE`）、折叠空白、小写，取前 80 字符；`code_fingerprint` 明确注释「Do not lower-case: code is case-sensitive」。
- `algo/comment_identity.py:16-19` 身份标记枚举；`:115-125` `comment_matches_identity()` 只在前 5 行（`_REVIEW_IDENTITY_HEADER_LINES = 5`）内做精确行匹配，避免正文引用被误判。
- `git_providers/git_provider.py:1066-1131` `publish_persistent_comment_full()`：找到 `comment_to_update` 就 `self.edit_comment(comment, pr_comment_updated)`，并插入 `#### (Review updated until commit {latest_commit_url})`。
- `git_providers/github_provider.py:939-1005` `publish_inline_comments()` 里接 `get_inline_comment_store(self)`，并对指纹做 `store.seen(body_fp) or store.seen(code_fp)` 过滤；注释解释为何不用 position：
  ```python
  # GitHub committable comments are anchored by diff position, which
  # shifts as the PR gains commits; anchor the fingerprint on the file
  # path and comment content instead so it stays stable across runs.
  body_fp = body_fingerprint(path, None, body)
  ```
- 开关：`settings/configuration.toml:76 persistent_inline_comments = false`；`settings/configuration.toml:187 persistent_comment=true`；`:190 persistent_finding_state=true`；`:194 max_previous_findings_chars = 8000`。

**置信度**：`源码已证实`

---

## 2. 新 push 的语义：全量还是 delta，diff 基线是什么

**答案**：**默认是全量重审**。增量模式只在显式传 `-i`（`/review -i`）时才启用，且必须同时满足「存在上一次 review 评论」「过得了提交数与时间阈值门」，否则自动回退全量。增量模式的**基线不是 base SHA，而是「上一次 review 时间之前的最后一个 commit」**：从最新 commit 往回走，`committer_date > previous_review.created_at` 的进入 `commits_range`，第一个不满足的成为 `last_seen_commit`，其 SHA（`incremental.last_seen_commit_sha`）就是增量 diff 的 old-side，文件内容按该 SHA 重新抓取并**重算 patch**。非增量的全量 diff 基线是 **merge base（`compare(base.sha, head.sha).merge_base_commit`）而不是 `pr.base.sha`**，这是为了防目标分支前进导致的假 diff。注意「优先 committer date 而非 author date」这一细节：rebase 会改写内容但保留 author date。

**证据**：
- `tools/pr_reviewer.py:286-293`：
  ```python
  def parse_incremental(self, args: List[str]):
      is_incremental = False
      if args and len(args) >= 1:
          arg = args[0]
          if arg == "-i":
              is_incremental = True
  ```
- `tools/pr_reviewer.py:1617-1665` `_can_run_incremental_review()`：`if self.is_auto and not self.incremental.first_new_commit_sha: return False`；`if self.incremental.commits_range is None: ... self.incremental.is_incremental = False; return False`；随后用 `minimal_commits_for_incremental_review` / `minimal_minutes_for_incremental_review` + `require_all_thresholds_for_incremental_review` 组合判定。
- `git_providers/github_provider.py:290-316` `_get_incremental_commits()`：`self.previous_review = self.get_previous_review(full=True, incremental=True)`；无上次 review 时 `self.incremental.is_incremental = False`（日志 “No previous review found, will review the entire PR”）；分支被整体 rebase 导致无基线 commit 时也回退全量（注释：“Incremental review cannot anchor a base commit … falling back to a full review”）。
- `git_providers/github_provider.py:318-335` 基线锚定：
  ```python
  def _commit_timeline_date(commit):
      """Prefer the committer date: rebasing rewrites content but preserves the author
      date, so anchoring on it classifies rewritten commits as already-reviewed."""
      committer_date = getattr(getattr(commit.commit, 'committer', None), 'date', None)
      return committer_date or commit.commit.author.date

  def get_commit_range(self):
      last_review_time = self.previous_review.created_at
      ...
      if self._commit_timeline_date(self.pr_commits[index]) > last_review_time:
          self.incremental.first_new_commit = self.pr_commits[index]
      else:
          self.incremental.last_seen_commit = self.pr_commits[index]
          break
  ```
- `git_providers/github_provider.py:508-512` 增量 old-side 内容与 patch 重算：
  ```python
  if self.incremental.is_incremental and self.unreviewed_files_map:
      original_file_content_str = self._get_pr_file_content(
          file, self.incremental.last_seen_commit_sha, path=old_filename)
      patch = load_large_diff(file.filename, new_file_content_str, original_file_content_str)
      self.unreviewed_files_map[file.filename] = patch
  ```
- `git_providers/github_provider.py:517-529` 全量 diff 用 merge base：
  ```python
  compare = repo.compare(pr.base.sha, pr.head.sha)
  merge_base_commit = compare.merge_base_commit
  ...
  get_logger().info(f"Using merge base commit {merge_base_commit.sha} instead of base commit ")
  ```
- diff 组装与裁剪入口：`algo/pr_processing.py:206-381` `get_pr_diff()`（先 `pr_generate_extended_diff` 试全量，超预算再 `pr_generate_compressed_diff`）、`:907-1082` `get_pr_multi_diffs()`。
- `tools/pr_reviewer.py:337-349`：增量但 `unreviewed_files_map` 为空时直接发 “Incremental Review Skipped / No files were changed since the [previous PR Review]”。

**置信度**：`源码已证实`（增量/全量的判定与基线锚定已逐行确认）。
> 未能证实：GitHub App 的 `auto_review` 自动命令是否默认附带 `-i` —— 需要读 `agent/pr_agent.py` / `servers/github_app.py` 的命令表，本次未读。

---

## 3. `suggestion` 一键应用块

**答案**：会生成 GitHub 原生的 committable ` ```suggestion ` 块，但「保证能应用」的策略是**范围校验 + 主动降级**，而不是逐字比对文件内容：`validate_comments_inside_hunks()` 检查建议的行号范围是否落在 diff hunk 内；落在外面（或只有部分落在里面）时，把 ` ```suggestion ` 块**替换成 ` ```diff ` 块**从而**取消一键应用**、并把 start/end 收敛到 hunk 范围内。行号定位失败的评论不会丢弃，而是降级为 **file-level 评论**（`subject_type="file"`）。GitHub 返回 422 时还有一条兜底路径：逐条重发，必要时**剥掉 suggestion 部分**、只把评论锚到第一行。从可见代码看，比对对象是模型自己给出的 `existing_code` / `improved_code`，**未见**把建议文本与真实 `file.head_file` 逐字比对的校验。

**证据**：
- `git_providers/github_provider.py:2202-2293` `validate_comments_inside_hunks()`；关键片段：
  ```python
  # make the suggestion non-committable, yet multi line
  new_start = max(suggestion['relevant_lines_start'], patch_range_min['start'])
  new_end = min(suggestion['relevant_lines_end'], patch_range_min['end'])
  ...
  existing_code = original_suggestion['existing_code'].rstrip() + "\n"
  improved_code = original_suggestion['improved_code'].rstrip() + "\n"
  ...
  # replace ```suggestion ... ``` with diff_code:
  body = replace_suggestion_blocks(body, diff_code)
  ```
- `git_providers/github_provider.py:925-937` `create_inline_comment()` 的降级：
  ```python
  position, absolute_position = find_line_number_of_relevant_line_in_file(
      self.diff_files, path, relevant_line_in_file, absolute_position)
  if position == -1:
      get_logger().info(f"Could not find position for {relevant_file} {relevant_line_in_file}")
      # Preserve the finding as a file-level review comment when no line can be anchored.
      return dict(body=body, path=path, subject_type="file")
  ```
- `git_providers/github_provider.py:911-922`：file-level 评论走 `create_review_comment(..., subject_type="file")`，注释说明 “The create_review endpoint does not accept subject_type in its payload.”
- `git_providers/github_provider.py:1220-1331` `_publish_inline_comments_fallback_with_verification()` 及其修复路径，注释 “Try fixing invalid comments by removing the suggestion part and setting the comment just on the first line”，实现用 `opener = re.search(r"(?<!`)(`{3,})suggestion", comment["body"])` 定位并剥离。
- `git_providers/github_provider.py:1342-1363` `_build_code_suggestion_payload()`；`:1344-1345` 超长时先剥 suggestion 再截断：
  ```python
  if len(body) > self.max_comment_chars:
      body = self.limit_output_characters(replace_suggestion_blocks(body, ""), self.max_comment_chars)
  ```
- `git_providers/github_provider.py:117-118`：`self.max_comment_chars = 65000`。

**置信度**：`源码已证实`（生成、提交、范围校验、降级、兜底四条链路均已确认）。
> 部分证实：「没有把 suggestion 与 `file.head_file` 逐字比对」这一否定性结论基于我读到的 `validate_comments_inside_hunks` 片段与相关调用点，未逐行读完整函数体与 `find_line_number_of_relevant_line_in_file`。

---

## 4. 误报抑制 / 学习

**答案**：系统里真正的「学习」机制是**人类 resolve 内联线程**：下次运行会读取该 PR 的 review threads，只接受「状态为 resolved **且** 可验证是 PR-Agent 自己开的 **且** 带 key-issue 标记」的线程，取其最后一条回复，作为 `state="DISMISSED"` 连同回复内容注入 prompt，让模型别再提；dismissed 条目在渲染时会**替换**同 id 的存储 finding。此外有一套 finding 生命周期（重复出现则 `reopened_count += 1`，修好了则进 `<details>✅ Resolved findings`），以及静态配置层面的抑制（`ignore_pr_*` 系列、`ignore.toml`、`extra_instructions`、per-repo / per-directory `.pr_agent.toml`）。**self-reflection 打分**在配置里只找到「作者自查勾选框」相关键（且注释明说 no effect），真正的 reflect 打分阈值未证实。

**证据**：
- `tools/pr_reviewer.py:696-717` `_load_dismissed_key_issues()`：
  ```python
  for thread in self.git_provider._iter_review_threads():
      if (thread.status != "resolved" or thread.authored_by_agent is not True
              or not KEY_ISSUE_LOCATION_MARKER_RE.search(thread.suggestion)):
          continue
      finding = {"path": thread.file, "body": strip_markers(thread.suggestion).strip(),
                 "line_start": thread.start_line, "line_end": thread.end_line}
      replies = thread.text_replies()
      if replies:
          finding["reply"] = replies[-1][1][:DISCUSSION_CONTEXT_MAX_MESSAGE_CHARS]
  ```
- `algo/review_finding_state.py:314-351` `render_previous_findings()`：渲染顺序 ACTIVE → DISMISSED → RESOLVED，且 `dismissed` 会替换同 id 存储项；`:330-337` 把 dismissed 标成 `"state": "DISMISSED"`。
- `algo/review_finding_state.py:240-260`：重复出现的 finding 会 `record["reopened_count"] = int(record.get("reopened_count", 0)) + 1`。
- `algo/review_finding_state.py:354-378` `_render_resolved_section()` 输出 `<summary>✅ Resolved findings</summary>`。
- `settings/configuration.toml:81-98` 静态抑制键：`ignore_pr_title`、`ignore_pr_target_branches`、`ignore_pr_source_branches`、`ignore_pr_labels`、`ignore_pr_authors`、`ignore_repositories`、`ignore_language_framework`。
- `settings/configuration.toml:287-290` self-review 相关键：
  ```toml
  demand_code_suggestions_self_review=false # add a checkbox for the author to self-review the code suggestions
  code_suggestions_self_review_text= "**Author self-review**: ..."
  approve_pr_on_self_review=false # no effect: ... and PR-Agent does not approve PRs
  fold_suggestions_on_self_review=true # no effect: ... suggestions are not folded when it is ticked
  ```
- `settings/configuration.toml:10`：`#model_reasoning="..." # dedicated reasoning model for self-reflection`。

**置信度**：误报抑制机制（dismissed / 生命周期 / 静态配置）为 `源码已证实`；**self-reflection 打分通道为 `无法证实`** —— `tools/pr_code_suggestions.py` 与 `settings/code_suggestions/pr_code_suggestions_reflect_prompts.toml` 本次未读（负责该文件的调研因配额中断）。

---

## 5. 大 PR 处理

**答案**：分四道闸。(1) **token 预算**：`max_model_tokens=32000` 硬上限 + soft/hard 两级输出预留（1500 / 1000 tokens）。(2) **压缩**：全量 diff 塞不进 soft 预算时，按「文件 token 数从大到小」逐个塞到预算为止，塞不下的文件收集成 `remaining_files_list`，并以纯文本清单（“Additional modified files (insufficient token budget to process):” 等）告知模型「这些文件你没看到」。(3) **分块（opt-in）**：`enable_large_pr_chunking=false` 默认关，开启后用 `get_pr_multi_diffs` 把文件打包装进 `max_number_of_calls=3` 个 chunk 并行送模型，再合并各 chunk 结果，失败 chunk 会在正文里明确声明「未被覆盖」。(4) **超大单文件**：`large_patch_policy="clip"`（或 `"skip"`）对单个超限 patch 做裁剪并把它记入 `partial_files_list`。另外有文件数相关的闸：超过 `MAX_FILES_ALLOWED_FULL` 后不再拉取完整文件内容（只用 diff）；不可读文件不静默跳过，而是插入显式占位说明让模型「flag it for manual review」。

**证据**：
- `algo/pr_processing.py:36-38`：
  ```python
  OUTPUT_BUFFER_TOKENS_SOFT_THRESHOLD = 1500
  OUTPUT_BUFFER_TOKENS_HARD_THRESHOLD = 1000
  ```
- `settings/configuration.toml:51`：`max_model_tokens = 32000 # Limits the maximum number of tokens that can be used by any model...`
- `algo/pr_processing.py:27-34` 未覆盖文件清单的文案常量：
  ```python
  MORE_MODIFIED_FILES_ = "Additional modified files (insufficient token budget to process):\n"
  ADDED_FILES_ = "Additional added files (insufficient token budget to process):\n"
  MAX_FILTERED_FILES_TO_PROMPT = 50
  ```
- `algo/pr_processing.py:677-680` 压缩时的排序策略：
  ```python
  # sort each one of the languages in top_langs by the number of tokens in the diff
  sorted_files.extend(sorted(lang['files'], key=lambda x: x.tokens, reverse=True))
  ```
- `algo/pr_processing.py:737-753` 多轮压缩（大 PR handling）与 `max_ai_calls`：
  ```python
  NUMBER_OF_ALLOWED_ITERATIONS = get_settings().pr_description.get("max_ai_calls", 4) - 1
  ```
- `settings/configuration.toml:210-213`：
  ```toml
  # large-diff chunking (opt-in). When the token budget leaves files out of the review, split the diff
  # into chunks, review each chunk, and merge the per-chunk results into one review.
  enable_large_pr_chunking=false
  max_number_of_calls=3 # maximum number of chunk review calls, used only when enable_large_pr_chunking is true
  ```
- `tools/pr_reviewer.py:1350-1358` 分块汇总声明与失败 chunk 声明（`review_chunk_count` / `review_failed_chunk_count`）。
- `algo/pr_processing.py:438-459` `clip_single_patch()` 与 `large_patch_policy`：
  ```python
  if get_settings().config.get("large_patch_policy", "skip") != "clip":
      get_logger().warning(f"Patch too large, skipping: {filename}")
  ```
- `git_providers/github_provider.py:494-499` 文件数闸：
  ```python
  if counter_valid >= MAX_FILES_ALLOWED_FULL and patch and not self.incremental.is_incremental:
      avoid_load = True
      if counter_valid == MAX_FILES_ALLOWED_FULL:
          get_logger().info("Too many files in PR, will avoid loading full content for rest of files")
  ```
- `tools/pr_reviewer.py:1360-1376` 未覆盖上报（`enable_review_coverage_footer`，`MAX_REVIEW_COVERAGE_FILES = 50`）：
  ```python
  (self.remaining_files_list, "were not included in this review because of the token budget"),
  (self.partial_files_list, "had patches clipped before analysis to fit the token budget (partial input coverage)"),
  ```
- `algo/pr_processing.py:588-601` `_unreadable_file_notice()`，注释解释为何不能省略：“Omitting it would make the gap invisible … Telling the model explicitly keeps the failure visible so it can flag the file for manual review.”
- `settings/configuration.toml:71 large_patch_policy = "clip"`；`:209 enable_review_coverage_footer=true`；`:238`（`[pr_description]`）`enable_large_pr_handling=true`。

**置信度**：`源码已证实`

---

## 6. 跨文件 finding 的归属

**答案**：**不支持一条 finding 跨多个文件**。review 输出 schema 里每个 key issue 只有**一个** `relevant_file` 加 `relevant_lines_start/end`；发布内联评论前会做硬校验：文件必须存在于本次 diff 中，且 `end_line` 不能超过该文件 head 版本的行数，否则**不发布内联评论、退回摘要评论**（日志 “keeping it in the summary”）。此外发布后还有**发布后验证**：用 `get_recent_inline_comment_bodies()` 复查指纹是否真的出现，验证失败的 finding 也会被退回摘要。无法锚定到具体行的建议则降级为 **file-level 评论**（`subject_type="file"`）。

**证据**：
- `tools/pr_reviewer.py:1449-1465` `_build_key_issue_comment()` 的硬校验：
  ```python
  if not relevant_file or not issue_content or start_line < 1 or end_line < start_line:
      get_logger().warning("Review finding has no usable location, keeping it in the summary", ...)
      return None
  ...
  if not file.head_file or end_line > len(file.head_file.splitlines()):
      get_logger().warning("Review finding points past the end of the file, keeping it in the summary", ...)
      return None
  ```
- `tools/pr_reviewer.py:1474-1487` `_published_inline_key_issue_fingerprints()`：调 `get_recent_inline_comment_bodies()` 复核，异常时返回空集并保持 finding 在摘要里。
- `tools/pr_reviewer.py:1548-1572`：逐 location fingerprint 判断是否真的发布成功，未成功的 `remaining_issues.extend(...)` 回摘要。
- `git_providers/github_provider.py:933-937`：position 找不到时降级为 `subject_type="file"` 的 file-level 评论。
- `algo/comment_identity.py:25-28` 存在未锚定建议的身份标记（说明存在「无锚点」这一类输出）：
  ```python
  class PRCodeSuggestionsIdentity(str, Enum):
      SUMMARY = "<!-- pr-agent:improve:summary -->"
      NO_SUGGESTIONS = "<!-- pr-agent:improve:no-suggestions -->"
      UNANCHORED = "<!-- pr-agent:improve:unanchored -->"
  ```

**置信度**：单文件归属 + 校验 + 退回摘要为 `源码已证实`。
> 部分证实：`UNANCHORED` 这一「未锚定建议」的**完整发布路径**（它最终落在哪个评论、附到哪个文件/行）我未追完整调用链；`/improve` 摘要表格的具体渲染我未读 `tools/pr_code_suggestions.py`。

---

## 7. 状态 / 记忆持久化

**答案**：**全部存在 GitHub 自身的对象里，没有外部 DB、没有磁盘状态文件**。具体三处：(1) **持久 review 评论正文**里藏着身份标记（`<!-- pr-agent:review:full -->`）与版本化 finding 状态 JSON（`<!-- pr-agent-review-state:v1 {...} -->`），这是跨运行的「记忆」主体；(2) **内联评论正文**里藏着去重指纹标记（`<!-- pr-agent-dedup: ... -->`）；(3) **PR 标签**被用于承载审查结论（`Review effort N/5`、`Possible security concern`），写入时会先读现有标签并只过滤掉自己上次写的同类标签，避免覆盖人类标签。另外「上一次 review 评论」本身也被复用为增量锚点（取其 `created_at`）——即状态是**隐式存在评论时间戳里**的。

**证据**：
- `algo/review_finding_state.py:168-177` `serialize_review_state()`，含 HTML 注释逃逸处理：
  ```python
  payload = json.dumps(state, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
  # An HTML comment ends at the first "-->" or "--!>", and a finding quotes whatever the
  # diff contains. Escaping the ">" keeps the marker one comment; ...
  payload = payload.replace("-->", "--\\u003e").replace("--!>", "--!\\u003e")
  return f"<!-- pr-agent-review-state:v{STATE_SCHEMA_VERSION}\n{payload}\n-->"
  ```
- `algo/review_finding_state.py:381-421` `append_review_state()`：把已解决区块 + 标记追加进正文，并在有 `max_chars` 时**优先保留完整标记、只裁剪人读文本**（注释：“The optional limit is reserved for the complete hidden marker.”）。
- `algo/review_finding_state.py:105-128` `_is_valid_state()`：schema_version 不符、findings 非 list、finding_id 重复、`reopened_count` 非非负 int 等一律判非法（损坏状态视为不安全，不推进）。
- `tools/pr_reviewer.py:1676-1734` `set_review_labels()`：读 `get_pr_labels(update=True)`，过滤自己上次写的 `review effort` / `possible security concern`，读失败时**放弃写入**：
  ```python
  # The read failed with no snapshot to fall back on. publish_labels
  # replaces the whole set, so publishing would delete human labels.
  ```
- `git_providers/github_provider.py:337-346` `get_previous_review()`：倒序扫 issue comments，用身份标记（含 legacy 可见标题前缀）找上一次 review。`algo/comment_identity.py:140-147` 说明返回「稳定标记 + legacy 可见前缀」以支持迁移。

**置信度**：`源码已证实`

---

## 8. 正式 review verdict

**答案**：**默认只发评论，不提交 verdict**。所有内联评论的 `create_review` 都硬编码 `event="COMMENT"`，review 正文走普通 issue comment；全仓库 grep **没有任何 `REQUEST_CHANGES`**。代码里**确实存在** `auto_approve()`（会调 `create_review(event="APPROVE")`），但它在 reviewer 中的**唯一调用点被注释掉了**，且配置文件里明确写着 “that caller is currently commented out”，默认 `enable_auto_approval=false`。所以默认策略是「评论 + 标签」，verdict 需要使用者自己开（而且当前开不了 APPROVE，因为调用点被注释）。另有替代输出通道 `[github] publish_as_check_run`，把结果发成 GitHub Check 而非评论(开启时持久 finding 状态会被自动禁用，因为 check run 载不了状态标记)。

**证据**：
- `git_providers/github_provider.py:993-995`（与备用路径 `:1231`）：
  ```python
  # publish all comments in a single message
  self.pr.create_review(commit=self.last_commit_id, event="COMMENT", comments=comments)
  ```
- `git_providers/github_provider.py:888-909` `publish_comment()` → `self.pr.create_issue_comment(pr_comment)`。
- `git_providers/github_provider.py:2189-2192`：
  ```python
  def auto_approve(self) -> bool:
      try:
          res = self.pr.create_review(event="APPROVE")
          if res.state == "APPROVED":
  ```
- `tools/pr_reviewer.py:323-326`（调用点被注释）：
  ```python
  # if isinstance(self.args, list) and self.args and self.args[0] == 'auto_approve':
  #     get_logger().info(f'Auto approve flow PR: {self.pr_url} ...')
  #     self.auto_approve_logic()
  #     return None
  ```
- `tools/pr_reviewer.py:1738-1750` `auto_approve_logic()` 本体仍在，受 `config.enable_auto_approval` 控制。
- `settings/configuration.toml:31`：
  ```toml
  enable_auto_approval = false # when true, /review may auto-approve a PR via auto_approve_logic(); that caller is currently commented out
  ```
- `settings/configuration.toml:289`：`approve_pr_on_self_review=false # no effect: ... PR-Agent does not approve PRs`。
- `settings/configuration.toml:327`：`publish_as_check_run = false # when true, publish review/description/improve output as GitHub Checks instead of PR comments`；`tools/pr_reviewer.py:543-547` 开启时 `_review_finding_state_enabled()` 返回 False。
- 对比参考：`require_merge_recommendation` 只让**模型输出文本** `no_concerns_found (…) or needs_review (…) or changes_required (…)`，不改变 GitHub review 的 event 类型（`settings/configuration.toml:180`）。

**置信度**：`源码已证实`（含 `REQUEST_CHANGES` 的否定性结论，基于全仓库 grep 零命中）。

---

## 9. 评论数量上限与折叠

**答案**：多道闸叠加：review 的 key issues 条数由 `num_max_findings=3` 约束；单条评论长度由 provider 的 `max_comment_chars`（GitHub 实现为 65000）经 `limit_output_characters()` 硬截断；长内容用 `<details><summary>` 折叠（已解决 finding 区块、工具使用指南）；建议条目自带 importance 分级标签（去重指纹计算时专门剥掉 `[..., importance: N]`）。未覆盖文件列表用 `MAX_REVIEW_COVERAGE_FILES = 50` 截断后追加 “… and N more”。**「隐藏 state 标记优先于人类可读文本」是一个额外且重要的预算规则**（截断时先截人读部分，保住标记）。

**证据**：
- `settings/configuration.toml:198`：`num_max_findings = 3`；`tools/pr_reviewer.py:244` 注入 `"num_max_findings": get_settings().pr_reviewer.num_max_findings`。
- `git_providers/git_provider.py:1266-1274` `limit_output_characters()`：
  ```python
  def limit_output_characters(self, output: str, max_chars: int):
      """Truncate output to max_chars, including the truncation suffix."""
      ...
      suffix = "..."
      return output[:max_chars - len(suffix)] + suffix
  ```
- `git_providers/github_provider.py:118`：`self.max_comment_chars = 65000`；调用点 `:896`、`:913`、`:927`、`:1344-1345`、`:1368`。
- `<details>` 折叠：`algo/review_finding_state.py:365-378`（`<summary>✅ Resolved findings</summary>`）与 `tools/pr_reviewer.py:1379-1382`：
  ```python
  markdown_text += "<hr>\n\n<details> <summary><strong>💡 Tool usage guide:</strong></summary><hr> \n\n"
  ```
- `tools/pr_reviewer.py:1368-1376`：`displayed_files = files[:MAX_REVIEW_COVERAGE_FILES]` 后 `markdown_text += f"\n... and {remaining_count} more"`。
- 优先级/重要性分级的存在证据：`algo/inline_comment_dedup.py:57` `_TAG_RE = re.compile(r"\[[^\]]+?,\s*importance:\s*\d+\]", re.IGNORECASE)`（正文里带 `importance: N` 标签，算指纹前需剥掉）。
- `algo/review_finding_state.py:406-419`：标记优先的截断规则。

**置信度**：`源码已证实`（长度截断、折叠、覆盖清单截断、`num_max_findings`）。
> 未能证实：`/improve` 专用的建议条数上限键（例如是否存在 `max_number_of_suggestions` / `suggestions_score_threshold`）——我未在 `configuration.toml` 的 `[pr_code_suggestions]` 段逐行读全，也未读 `tools/pr_code_suggestions.py`。

---

## 10. 可观测性与成本

**答案**：**有 token 用量与「运行详情」的框架，但默认关闭且成本需显式开启**。配置里存在 `output_run_details=false`（注释：追加 model、tokens、time cost、AI calls 的运行详情段）与 `output_run_cost=false`（注释：收集 LiteLLM 估算的 API 成本），对应渲染函数 `show_run_details()`。token 用量确实被采集并放进结构化输出：`_prepare_pr_review()` 里把 `details.prompt_tokens / completion_tokens / total_tokens` 塞进 `structured_data["usage"]` 后再交给 provider 的结构化发布钩子（或 `review_json_output_path` 落盘）。降级模型会被记录（`record_model_used(model, is_fallback=...)`，chunk 合并时还会写 `details.models_used`）。**LLM 响应缓存未证实**——我确认的 `persistent_inline_comments` 是「评论去重」，不是模型响应缓存；本仓库确有 `pr_agent/telemetry/` 目录（meter/config/prometheus/registry/tracer），但内容本次未读。

**证据**：
- `settings/configuration.toml:68-70`：
  ```toml
  output_relevant_configurations=false
  output_run_details=false # if true, append an agent run details section (model, tokens, time cost, AI calls) to generated PR comments
  output_run_cost=false # if true, collect estimated LiteLLM API cost and include it inside the enabled run details section
  ```
- `tools/pr_reviewer.py:1384-1390`：`show_relevant_configurations(...)` 与 `show_run_details(self.git_provider.is_supported("gfm_markdown"))`。
- `tools/pr_reviewer.py:1315-1327` token 用量写进结构化输出：
  ```python
  details = get_run_details()
  usage = {}
  if details is not None and details.has_token_usage:
      usage = {
          "prompt_tokens": details.prompt_tokens,
          "completion_tokens": details.completion_tokens,
          "total_tokens": details.total_tokens,
      }
  structured_data["usage"] = usage
  ```
- `tools/pr_reviewer.py:53` 导入 `from pr_agent.algo.run_details import get_run_details, init_run_details, record_command_failure, record_model_used`；`:1171-1176` 分块合并时记录 `details.models_used = models` 与 `record_model_used(model, is_fallback=model != self._chunked_primary_model)`。
- `algo/pr_processing.py:868`：`record_model_used(model, is_fallback=i > 0)`（fallback 链里每个成功模型都会被记录）。
- `pr_agent/telemetry/` 目录存在（`config.py` / `meter.py` / `prometheus.py` / `prometheus_multiproc.py` / `registry.py` / `shutdown.py` / `tracer.py` / `types.py`）—— **内容未读**。
- 失败可见性（相邻机制）：`tools/pr_reviewer.py:506-511`：
  ```python
  if review_failed:
      # Record the failure here, where `review_failed` is consumed: ...
      record_command_failure()
  ```
  以及 `:516-523` 可选发布 `_review_failure_comment()`（`pr_reviewer.publish_review_failure_comment` 默认 true，`publish_error_details` 默认 false，走确定性错误归类、**不额外调 LLM**）。

**置信度**：`源码已证实`（配置键存在、token 采集与结构化输出路径确认）。
> `无法证实`：`algo/run_details.py` 的实际字段集合与 `show_run_details()` 的渲染内容、成本计算是否真的落地（是否调用 LiteLLM 的 cost 能力）、`telemetry/` 的具体指标、以及**是否存在 LLM 响应缓存**（磁盘/Redis/prompt-hash）。这些文件本次未读。

---

## 机制清单

- **隐藏 HTML 身份标记定位持久评论** — 在评论正文顶部插入 `<!-- pr-agent:review:full -->` 之类不可见标记，靠它找回并编辑上一条评论，而不是依赖可见标题或评论 ID 记忆。
- **编辑同一条持久评论而非新发评论** — `publish_persistent_comment_full()` 找到旧评论就 `edit_comment` 覆盖，并在其中插入 `(Review updated until commit …)` 更新行。
- **版本化 finding 状态 JSON 内嵌评论正文** — 把 `{schema_version, findings[], last_run{head_sha, complete, excluded_files}}` 序列化后藏进评论，实现零外部存储的跨运行记忆。
- **状态标记的 HTML 注释逃逸** — 序列化时把 payload 里的 `-->` / `--!>` 转义成 `--\u003e`，防止 finding 正文里引用的 diff 内容提前闭合注释。
- **标记优先的截断策略** — 正文超长时先截人类可读文本，保留完整状态标记（`append_review_state(..., max_chars)`）。
- **状态 schema 校验 + 损坏即保守阻断** — `_is_valid_state()` 校验版本/结构/去重，标记损坏时置 `_STATE_BLOCK_INVALID_MARKER` 并**不推进**持久状态，避免把损坏状态当成「问题都修好了」。
- **仅「完整 review + head 已变」才允许把 finding 标记为已修** — `allow_resolution` 同时要求无失败 chunk、无 token 预算遗漏文件、无被丢弃 finding，且 `previous_head_sha != head_sha`。
- **finding 指纹（file + 归一化正文，sha256[:12]）** — 归一化时剥掉 `**Suggestion:**` 前缀与 `importance` 标签、折叠空白、小写，取前 80 字符。
- **双指纹 OR 匹配（正文指纹 / suggestion 代码指纹）** — 同时覆盖「同文不同码」与「同码不同文」两种 LLM 复述方式；代码指纹不做小写化。
- **指纹锚在 file path + 内容而非 diff position** — 因为 committable 评论的 position 会随新 commit 漂移，用 position 会导致去重失效。
- **去重指纹以标记写入已发布评论、下轮扫描重建 seen 集合** — 无需外部存储；加载失败自动降级为「仅本次运行内去重」且不抛异常。
- **指纹释放机制** — 被自动清扫关闭的「过期线程」指纹会被 `release()`，以免抑制自己的替代评论；人类手动 resolved 的线程继续抑制。
- **人类 resolve 线程作为「别再提」的教学信号** — 读取 resolved 且可验证由 agent 创建且带 key-issue 标记的线程，取最后回复，以 DISMISSED 状态注入 prompt 并替换同 id finding。
- **上一轮 finding 以 JSON 回注 prompt** — 让模型沿用原措辞而非换个说法重报，并带字符预算（`max_previous_findings_chars=8000`）。
- **finding 重开计数** — 已 RESOLVED 的 finding 再次出现时 `reopened_count += 1` 并记 `reopened_at`。
- **已解决 finding 折叠区块** — `<details><summary>✅ Resolved findings</summary>` 收纳已修问题，避免正文膨胀。
- **增量基线 = 上次 review 之前最后一个 commit** — 以「commit 时间 > 上次 review `created_at`」切分，取第一个不满足的 commit 作为 old-side SHA。
- **增量锚定优先 committer date 而非 author date** — 因为 rebase 改写内容但保留 author date，用 author date 会把被改写的 commit 误判为已审。
- **增量 old-side 内容按 `last_seen_commit_sha` 重新抓取并重算 patch** — 而不是直接复用 GitHub 的 PR patch。
- **无法锚定基线时自动回退全量** — 找不到上次 review、或分支被整体 rebase 导致无 commit 早于上次 review 时，主动关掉 `is_incremental`。
- **增量阈值门（提交数 / 时间 / all-any 组合）** — `minimal_commits_for_incremental_review`、`minimal_minutes_for_incremental_review`、`require_all_thresholds_for_incremental_review`。
- **全量 diff 用 merge base 而非 base SHA** — `repo.compare(base.sha, head.sha).merge_base_commit`，防止目标分支前进产生假 diff。
- **merge commit 在增量扫描中跳过** — 避免把合并目标分支带入未审查文件集合。
- **soft/hard 两级 token 预算 + 输出预留** — 1500 / 1000 tokens 的输出缓冲，以及 `max_model_tokens` 全局硬上限。
- **大 diff 按文件 token 降序装箱** — 先装大文件、预算用尽即止，剩余进入 `remaining_files_list`。
- **未覆盖文件以纯文本清单告知模型** — “Additional modified files (insufficient token budget to process):”，让模型知道自己没看全。
- **多 chunk 并行审阅 + 结果合并 + 失败 chunk 显式声明** — `enable_large_pr_chunking`（默认关）+ `max_number_of_calls=3`，正文声明「N chunk(s) failed and are not covered by this review」。
- **超大单文件 clip / skip 策略** — `large_patch_policy = "clip"`，裁剪过的文件记入 `partial_files_list` 并在覆盖页脚说明「patch 被裁剪」。
- **文件数上限后停止拉取完整文件内容** — `MAX_FILES_ALLOWED_FULL` 之后只用 diff，避免大 PR 拖垮 API 配额。
- **不可读文件插入显式占位而非静默跳过** — 让「抓取失败」与「文件干净」在模型与人类眼里可区分。
- **review 覆盖页脚（未包含 + 被裁剪两类）** — `enable_review_coverage_footer`，各自截断到 50 条后 “… and N more”。
- **suggestion 范围校验 + 主动降级为不可一键应用** — 行号不在 diff hunk 内时把 ` ```suggestion ` 换成 ` ```diff `，宁可取消 committable 也不发应用不了的补丁。
- **行号定位失败降级为 file-level 评论** — `subject_type="file"`，走单条 `create_review_comment` 端点。
- **内联发布 422 兜底：剥掉 suggestion 只锚第一行** — 逐条重发并修复，最大化「至少把问题说出来」。
- **内联发布的发布后验证** — 用 `get_recent_inline_comment_bodies()` 复查指纹是否真的出现，未验证成功的 finding 退回摘要评论。
- **finding 无可用位置时退回摘要而非丢弃** — 文件不在 diff、行号越界、缺内容都走同一条兜底路径。
- **findings 按 location 指纹合并候选评论** — 同一 file+line 区间的多条 issue 合并为一条内联评论。
- **只用 `event="COMMENT"`，不发 verdict** — 全仓库无 `REQUEST_CHANGES`；`auto_approve()` 存在但唯一调用点被注释且配置默认关。
- **`publish_as_check_run` 替代评论通道** — 可把 review/describe/improve 结果发成 GitHub Check；开启时自动禁用持久 finding 状态（check run 载不了状态标记）。
- **标签承载审查结论且保护人类标签** — 写 `Review effort N/5` / `Possible security concern` 前先读现有标签、只移除自己上次写的同类标签；读取失败则放弃写入。
- **fallback 模型链 + 逐模型记录** — `fallback_models` 依次重试，成功者用 `record_model_used(model, is_fallback=i > 0)` 记录；分块时汇总到 `details.models_used`。
- **run details / run cost 开关** — `output_run_details`、`output_run_cost`（默认均 false），token 用量写入结构化输出的 `usage` 字段。
- **确定性失败原因（不额外调 LLM）** — 用 allowlist 模式匹配错误文本给出人读原因，`publish_error_details` 默认 false；`record_command_failure()` 供 Action runner 与 reaction 结果消费。
- **身份标记的 legacy 迁移兼容** — `get_pr_review_comment_identifiers()` 同时返回新隐藏标记与旧可见标题前缀，且身份匹配只在前 5 行内做精确匹配以防误判。

---

## 附：未证实清单（配额中断，建议后续补齐）

1. **server 层事件流**：`servers/github_app.py` 处理的 webhook 事件集合、`issue_comment` / `pull_request_review` 的分发分支、以及**重复投递防护**（delivery id 缓存、已处理评论 id 集合、bot 自答过滤）——未读。
2. **`auto_review` 自动命令是否默认带 `-i`**（即「新 push 默认增量」还是「默认全量」的最终结论）——`agent/pr_agent.py` 命令表未读。基于 `pr_reviewer.parse_incremental` 仅识别显式 `-i`，当前证据倾向于默认全量，但未证实。
3. **命令清单与别名**（`/review` `/improve` `/ask` `/describe` 等及其参数解析）——未读。
4. **`tools/pr_code_suggestions.py` 内部**：真正的 self-reflection 打分阈值、低分建议处置、`/improve` 专用条数上限键、摘要表格与内联评论的链接构造方式——未读。
5. **`algo/run_details.py` / `algo/run_output.py` / `telemetry/`**：run details 的确切字段、成本是否真实计算、Prometheus 指标名——未读。
6. **是否存在 LLM 响应缓存**（磁盘/Redis/prompt-hash 键）——未证实。已确认的 `persistent_inline_comments` 属于评论去重，不是模型缓存。
7. **`algo/git_patch_processing.py`**（本次只通过 `pr_processing.py` 的调用点间接了解 `decouple_and_convert_to_hunks_with_lines_numbers` / `extend_patch` / `handle_patch_deletions`）——未直接通读。
8. **并发/重入保护**（同一 PR 同时触发多次运行时是否有锁）——未读 `agent/request_policy.py`。
