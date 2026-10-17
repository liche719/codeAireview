package com.codepilot.module.tool.impl;

import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.review.classifier.ReviewFileClassifier;
import com.codepilot.module.tool.dto.ToolCheckRequest;
import com.codepilot.module.tool.dto.ToolCheckResult;
import com.codepilot.module.tool.rule.DeterministicReviewRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub Actions workflow 的确定性安全规则（R13）。
 *
 * <h2>为什么用确定性规则而不是交给 LLM</h2>
 * 这里要查的都是<b>可枚举的模式</b>：`pull_request_target` 配合 checkout PR head、
 * 直接把用户可控输入拼进 {@code run:}、`write-all` 权限、第三方 action 未固定 SHA。
 * 可枚举的东西交给规则做，比让模型"看着办"更稳定、更可解释，也不会偶尔漏掉。
 *
 * <h2>只判新增行，且只报高置信模式</h2>
 * 两个降噪措施是刻意的：
 * <ol>
 *   <li><b>只看本 PR 新增的行。</b>上下文行里的既有写法不是这次引入的，
 *       报出来等于让人为历史代码负责，会迅速消耗掉对规则的信任。</li>
 *   <li><b>只报可枚举的高置信模式，措辞避免绝对化，并附上触发片段。</b>
 *       workflow 是"配置即代码"，同一个意图有多种写法；安全规则的误报代价特别高——
 *       一次误报就会让人开始忽略整类提示。</li>
 * </ol>
 *
 * <p>因此 {@code pull_request_target} <b>单独出现不报</b>：它是合法的，
 * 危险的是它又去 checkout 了 PR 的 head。只有两者同时出现在本次新增行里才报。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "codepilot.tools", name = {"enabled", "ci-workflow-risk-enabled"},
        havingValue = "true", matchIfMissing = true)
public class CiWorkflowRiskTool implements DeterministicReviewRule {

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$");

    /** `uses: owner/repo@ref`；ref 不是 40 位十六进制即为可变引用。 */
    private static final Pattern USES_PATTERN =
            Pattern.compile("^\\s*-?\\s*uses:\\s*([A-Za-z0-9_.-]+)/([A-Za-z0-9_./-]+)@([^\\s#\"']+)");

    private static final Pattern PINNED_SHA = Pattern.compile("^[0-9a-fA-F]{40}$");

    /** 这些 owner 的 action 用版本标签是社区常态，不算供应链风险。 */
    private static final List<String> FIRST_PARTY_ACTION_OWNERS = List.of("actions", "github");

    /** 未受信任输入被直接插值进脚本时的高危来源。 */
    private static final List<String> UNTRUSTED_INTERPOLATION_SOURCES = List.of(
            "github.event.", "github.head_ref", "inputs.");

    private static final int EVIDENCE_MAX_CHARS = 120;

    @Override
    public String id() {
        return "CI_WORKFLOW_RISK_RULE";
    }

    @Override
    public int order() {
        return 150;
    }

    /** 只对 workflow 文件生效——其余文件直接跳过，不产生任何开销。 */
    @Override
    public boolean supports(ToolCheckRequest request) {
        return request != null && isWorkflowPath(request.getFilePath());
    }

    @Override
    public List<ToolCheckResult> check(ToolCheckRequest request) {
        if (request == null) {
            return List.of();
        }
        long startTime = System.currentTimeMillis();
        try {
            List<ToolCheckResult> results = scan(request.getPatch());
            log.info("CiWorkflowRiskTool executed, filePath={}, issueCount={}, costTimeMs={}",
                    request.getFilePath(), results.size(), System.currentTimeMillis() - startTime);
            return results;
        } catch (Exception exception) {
            log.warn("CiWorkflowRiskTool failed, filePath={}, message={}",
                    request.getFilePath(), SensitiveDataSanitizer.redact(exception.getMessage()));
            return List.of();
        }
    }

    static boolean isWorkflowPath(String filePath) {
        String normalized = ReviewFileClassifier.normalizePath(filePath);
        return normalized.startsWith(".github/workflows/")
                && (normalized.endsWith(".yml") || normalized.endsWith(".yaml"));
    }

    /**
     * 扫描 patch 的<b>新增行</b>。
     *
     * <p>需要跨行聚合的只有 {@code pull_request_target} 与 head checkout 这一对，
     * 所以先收集两者是否出现，再逐行判定单行模式。</p>
     */
    static List<ToolCheckResult> scan(String patch) {
        if (!StringUtils.hasText(patch)) {
            return List.of();
        }
        List<AddedLine> addedLines = collectAddedLines(patch);
        if (addedLines.isEmpty()) {
            return List.of();
        }

        boolean usesPullRequestTarget = addedLines.stream().anyMatch(line -> containsTrigger(line.text(), "pull_request_target"));
        boolean usesPullRequest = addedLines.stream().anyMatch(line -> containsTrigger(line.text(), "pull_request"));
        boolean checksOutPullRequestHead = addedLines.stream()
                .anyMatch(line -> line.text().contains("github.event.pull_request.head"));

        List<ToolCheckResult> results = new ArrayList<>();
        if (usesPullRequestTarget && checksOutPullRequestHead) {
            results.add(pullRequestTargetWithHeadCheckout(addedLines));
        }
        scanRunBlocks(addedLines, results);
        addedLines.stream()
                .filter(line -> line.text().replace(" ", "").contains("permissions:write-all"))
                .findFirst()
                .ifPresent(line -> results.add(writeAllPermissions(line)));
        addedLines.stream()
                .filter(line -> line.text().contains("secrets.") && usesPullRequest && !usesPullRequestTarget)
                .findFirst()
                .ifPresent(line -> results.add(secretOnPullRequest(line)));
        scanUnpinnedActions(addedLines, results);
        return List.copyOf(results);
    }

    // ------------------------------------------------------------------
    // 各模式
    // ------------------------------------------------------------------

    /**
     * 经典注入向量：{@code pull_request_target} 会带着 base 仓库的 secrets 与写权限运行，
     * 而它 check out PR head 就等于把攻击者的代码放进这个高权限上下文里执行。
     */
    private static ToolCheckResult pullRequestTargetWithHeadCheckout(List<AddedLine> addedLines) {
        String evidence = evidenceOf(addedLines, "github.event.pull_request.head");
        return ToolCheckResult.atLine(
                lineNumberOf(addedLines, "github.event.pull_request.head"),
                "SECURITY",
                "HIGH",
                "`pull_request_target` 配合 checkout PR head 存在代码注入风险",
                "本 workflow 同时使用了 `pull_request_target` 与包含 `github.event.pull_request.head` 的 checkout。"
                        + "`pull_request_target` 在 base 仓库上下文运行，带有 secrets 与写权限；"
                        + "checkout PR head 会让该 PR 的代码在这个高权限上下文里被执行。"
                        + "证据：" + evidence,
                "若确实需要读取 PR 内容，不要 checkout head，改为通过 API 读取文件；"
                        + "若必须运行 PR 代码，请改用 `pull_request` 触发，并把权限收敛到只读。"
        );
    }

    /**
     * 用户可控输入直接插值进 {@code run:} 会被 shell 当作命令解析
     * （PR 标题里放一个 `$(...)` 即可）。GitHub 官方建议先经 `env:` 传递。
     */
    private static void scanRunBlocks(List<AddedLine> addedLines, List<ToolCheckResult> results) {
        boolean inRunBlock = false;
        int runKeyIndent = -1;

        for (AddedLine line : addedLines) {
            String trimmed = line.text().trim();
            String runBody = runKeyBody(trimmed);

            if (runBody != null) {
                inRunBlock = true;
                // 取 `run` 这个键本身的列位置，而不是整行缩进：`- run: |` 这种列表项写法下，
                // 两者差 2 列，用整行缩进会把同级的 `shell:` 之类的兄弟键也算进脚本块。
                runKeyIndent = line.text().indexOf("run:");
                if (!runBody.startsWith("|") && !runBody.startsWith(">") && hasUntrustedInterpolation(runBody)) {
                    results.add(scriptInjection(line, runBody));
                }
                continue;
            }
            if (inRunBlock) {
                boolean stillInBlock = trimmed.isEmpty() || leadingSpaces(line.text()) > runKeyIndent;
                if (!stillInBlock) {
                    inRunBlock = false;
                    runKeyIndent = -1;
                } else if (hasUntrustedInterpolation(trimmed)) {
                    results.add(scriptInjection(line, trimmed));
                }
            }
        }
    }

    /**
     * 取出 {@code run:} 之后的脚本内容；不是 run 键则返回 {@code null}。
     *
     * <p>必须同时认 {@code run: xxx} 与列表项写法 {@code - run: xxx}——
     * 后者是 {@code steps:} 里最常见的形式，只认前者会让整个 run 块检查形同虚设。</p>
     */
    private static String runKeyBody(String trimmed) {
        String candidate = trimmed.startsWith("-") ? trimmed.substring(1).trim() : trimmed;
        if (!candidate.startsWith("run:")) {
            return null;
        }
        return candidate.substring("run:".length()).trim();
    }

    private static void scanUnpinnedActions(List<AddedLine> addedLines, List<ToolCheckResult> results) {
        for (AddedLine line : addedLines) {
            Matcher matcher = USES_PATTERN.matcher(line.text());
            if (!matcher.find()) {
                continue;
            }
            String owner = matcher.group(1).toLowerCase(Locale.ROOT);
            String ref = matcher.group(3).trim();
            if (FIRST_PARTY_ACTION_OWNERS.contains(owner) || PINNED_SHA.matcher(ref).matches()) {
                continue;
            }
            // 每个文件里最多报一条，避免一个 workflow 引入十个 action 时刷屏。
            results.add(unpinnedAction(line, matcher.group(1) + "/" + matcher.group(2), ref));
            return;
        }
    }

    private static ToolCheckResult scriptInjection(AddedLine line, String evidence) {
        return ToolCheckResult.atLine(
                line.lineNumber(),
                "SECURITY",
                "HIGH",
                "workflow 脚本里直接插值了用户可控输入",
                "`run:` 中出现了未经处理的 `${{ ... }}` 引用。这类值（PR 标题、分支名、issue 正文等）"
                        + "由外部用户控制，插入 shell 脚本后会被当作命令解析，构造 `$(...)` 或反引号即可执行任意命令。"
                        + "证据：" + truncate(evidence),
                "改为先经 `env:` 传入，再在脚本里以 `\"$VAR\"` 引用——"
                        + "例如 `env: { TITLE: ${{ github.event.pull_request.title }} }` 配 `run: echo \"$TITLE\"`。"
        );
    }

    private static ToolCheckResult writeAllPermissions(AddedLine line) {
        return ToolCheckResult.atLine(
                line.lineNumber(),
                "SECURITY",
                "MEDIUM",
                "workflow 声明了 `permissions: write-all`",
                "`write-all` 把仓库的全部写权限授予该 workflow 的所有 job。"
                        + "一旦其中任一步骤被注入（例如上面提到的脚本注入），攻击者就同时拿到了删除分支、"
                        + "改 release、推代码的能力。证据：" + truncate(line.text().trim()),
                "改为在 job 级只授予确实需要的那几项权限，例如 `permissions: { contents: read, pull-requests: write }`。"
        );
    }

    private static ToolCheckResult secretOnPullRequest(AddedLine line) {
        return ToolCheckResult.atLine(
                line.lineNumber(),
                "SECURITY",
                "MEDIUM",
                "`pull_request` 触发的 workflow 引用了 secrets",
                "由 `pull_request` 触发的 workflow 在 fork PR 上<b>拿不到</b> secrets，"
                        + "这里引用后取到的是空值，依赖它的步骤会静默失败或行为与预期不符；"
                        + "若该仓库允许 fork PR 直接获得 secrets，则同时是泄露风险。"
                        + "证据：" + truncate(line.text().trim()),
                "确认这一步在 fork PR 上是否必须成功：需要 secrets 的场景请改用 `pull_request_target` "
                        + "并严格限制 checkout 与权限，或把该步骤拆分到受信任的 workflow 中执行。"
        );
    }

    private static ToolCheckResult unpinnedAction(AddedLine line, String action, String ref) {
        return ToolCheckResult.atLine(
                line.lineNumber(),
                "SECURITY",
                "LOW",
                "第三方 action 未固定到 commit SHA",
                "`" + action + "@" + ref + "` 使用可变引用：上游可以随时把该标签指向新代码，"
                        + "而新代码会在你的 CI 里拿到 secrets。证据：" + truncate(line.text().trim()),
                "改为固定到完整的 40 位 commit SHA（可同时用注释标注版本，便于阅读与升级）。"
        );
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static List<AddedLine> collectAddedLines(String patch) {
        List<AddedLine> addedLines = new ArrayList<>();
        int newLine = 0;
        boolean inHunk = false;

        for (String line : patch.split("\\R", -1)) {
            Matcher matcher = HUNK_HEADER.matcher(line);
            if (matcher.matches()) {
                newLine = Integer.parseInt(matcher.group(1));
                inHunk = true;
                continue;
            }
            if (!inHunk) {
                continue;
            }
            if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("\\ No newline")) {
                continue;
            }
            if (line.startsWith("+")) {
                addedLines.add(new AddedLine(newLine, line.substring(1)));
                newLine++;
                continue;
            }
            if (!line.startsWith("-")) {
                // 上下文行占新侧行号，但不参与判定。
                newLine++;
            }
        }
        return addedLines;
    }

    /**
     * 判断某一行是否声明了指定触发器。
     *
     * <p>YAML 里同一个意图有多种写法，必须都认：
     * 单行 {@code on: pull_request}、列表 {@code - pull_request}、映射键 {@code pull_request:}、
     * 流式列表 {@code on: [pull_request]}。只认其中一种会让规则静默失效——
     * 最初只处理了映射键形式，于是最常见的 {@code on: pull_request_target} 反而漏判。</p>
     *
     * <p>匹配用<b>整段相等</b>而不是 {@code contains}：否则查 {@code pull_request} 时
     * 会命中 {@code pull_request_target}，把"能拿到 secrets"的场景误报成"拿不到 secrets"。</p>
     */
    private static boolean containsTrigger(String text, String trigger) {
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return false;
        }
        String body = trimmed.replace("\"", "").replace("'", "");
        if (body.startsWith("on:")) {
            body = body.substring("on:".length()).trim();
        }
        if (body.startsWith("-")) {
            body = body.substring(1).trim();
        }
        int commentIndex = body.indexOf(" #");
        if (commentIndex >= 0) {
            body = body.substring(0, commentIndex).trim();
        }
        if (body.startsWith("[") && body.endsWith("]")) {
            body = body.substring(1, body.length() - 1);
            for (String item : body.split(",")) {
                if (trigger.equals(item.trim())) {
                    return true;
                }
            }
            return false;
        }
        return body.equals(trigger) || body.equals(trigger + ":") || body.startsWith(trigger + ": ");
    }

    private static boolean hasUntrustedInterpolation(String text) {
        if (!StringUtils.hasText(text) || !text.contains("${{")) {
            return false;
        }
        return UNTRUSTED_INTERPOLATION_SOURCES.stream().anyMatch(text::contains);
    }

    private static int leadingSpaces(String text) {
        int count = 0;
        while (count < text.length() && text.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static Integer lineNumberOf(List<AddedLine> addedLines, String needle) {
        return addedLines.stream()
                .filter(line -> line.text().contains(needle))
                .map(AddedLine::lineNumber)
                .findFirst()
                .orElse(null);
    }

    private static String evidenceOf(List<AddedLine> addedLines, String needle) {
        return addedLines.stream()
                .filter(line -> line.text().contains(needle))
                .map(line -> truncate(line.text().trim()))
                .findFirst()
                .orElse("(未捕获到具体片段)");
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= EVIDENCE_MAX_CHARS ? value : value.substring(0, EVIDENCE_MAX_CHARS) + "…";
    }

    private record AddedLine(int lineNumber, String text) {
    }
}
