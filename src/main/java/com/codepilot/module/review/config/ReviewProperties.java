package com.codepilot.module.review.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "codepilot.review")
public class ReviewProperties {

    private int maxFilesPerTask = 30;

    private int maxSummaryFindings = 20;

    /**
     * 是否在 Summary Comment 中输出「审查覆盖」区块，说明哪些文件没有被审查以及原因。
     *
     * <p>默认开启：不报告覆盖度会让用户把"只审了 5 / 200 个文件"的摘要误读成完整结论。</p>
     */
    private boolean coverageReportEnabled = true;

    /** 覆盖度区块中最多逐个列出多少个未审查文件，超出部分折叠。 */
    private int coverageMaxListedFiles = 20;

    /**
     * 最低覆盖比例（已审文件数 / 变更文件数）。低于该比例时在摘要顶部给出醒目告警。
     *
     * <p>取 0 到 1；&lt;=0 表示关闭该告警。</p>
     */
    private double minCoverageRatio = 0.5;

    /**
     * 是否启用跨 headSha 的 finding 记忆。
     *
     * <p><b>默认关闭</b>：它会让"上次已提过的问题"不再重复评论，属于发布行为的改变，
     * 需要灰度。开启后 {@code suppress-repeated-findings} 才生效。</p>
     */
    private boolean findingMemoryEnabled = false;

    /** 记忆中已存在的 finding 是否跳过再次评论。默认开启（仅在记忆启用时生效）。 */
    private boolean suppressRepeatedFindings = true;

    /**
     * 是否允许把"本次未再现"的 finding 判定为 RESOLVED。
     *
     * <p>即使开启，也只有当本次是<b>完整审查</b>且 head sha 确实变化时才会生效——
     * 详见 {@code ReviewFindingMemoryService}。</p>
     */
    private boolean allowResolution = true;

    /**
     * 是否读取 GitHub review thread 的 resolved 状态来做误报抑制（R7）。
     *
     * <p><b>默认关闭</b>：它需要 GraphQL 权限，并且会改变"提不提某个问题"的行为。
     * 开启后任何失败都只会降级为不抑制，不会影响审查本身。</p>
     */
    private boolean dismissalSyncEnabled = false;

    /**
     * 是否允许把 finding 发布成<b>多行区间</b>评论（R4）。
     *
     * <p>默认开启：他只是一个展示形态的改进，且 {@code DiffLineMapper.mapRange} 校验不过时
     * 会自动降级为单行，不会丢失 finding。</p>
     */
    private boolean multilineCommentEnabled = true;

    /**
     * 是否生成 GitHub 一键应用的 {@code suggestion} 块（R5）。
     *
     * <p><b>默认关闭</b>：suggestion 被点击后会直接改用户代码，属写侧交互，
     * 需要先在沙箱里验证过再开。</p>
     */
    private boolean suggestionEnabled = false;

    /** 单条建议的最大行数，超过则不生成 suggestion（仍会以 diff 块展示）。 */
    private int suggestionMaxLines = 20;

    /** 校验不通过时，是否把 suggestion 降级成 diff 块展示。关闭则完全不展示建议代码。 */
    private boolean suggestionFallbackToDiff = true;

    /**
     * 运维追加的路径跳过规则（R11），逗号分隔，默认空。
     *
     * <p>按<b>子串</b>匹配归一化后的路径。内置规则覆盖常见的构建产物 / 锁文件 /
     * vendor / 生成物；这个开关用于补上项目特有的目录（例如自研的代码生成输出目录）。</p>
     *
     * <p>空规则会被忽略——一条空白规则若参与匹配会命中所有文件，把整个仓库静默排除掉。</p>
     */
    private List<String> extraSkipPatterns = new ArrayList<>();

    /**
     * 是否启用仓库内的路径级审查指令（R12）。
     *
     * <p><b>默认关闭</b>：这条能力本质是<b>把仓库内容注入 prompt</b>，是一个新的 prompt injection 面。
     * 虽然已经做了"只从 base 分支读 + 按不可信内容包装 + 限长"三重约束，
     * 但它仍然是新增的输入通道，应当显式开启。</p>
     */
    private boolean pathInstructionsEnabled = false;

    /** 规则文件在仓库中的路径（相对仓库根）。 */
    private String pathInstructionsFile = ".codepilot/rules.md";

    /** 全部指令合计的字符上限；超出部分丢弃，避免规则文件成倍放大每个文件的 prompt。 */
    private int pathInstructionsMaxChars = 8000;

    /**
     * 是否启用构建门禁（R17）。**默认关闭**。
     *
     * <p>门禁会写 commit status，被加进 required status checks 后会<b>阻塞他人合并</b>。
     * 误判的代价由别人承担，所以默认不开，且只信确定性规则。</p>
     */
    private boolean gateEnabled = false;

    /** 触发 failure 的最低 severity（`HIGH` / `MEDIUM` / `LOW`）。 */
    private String gateMinSeverity = "HIGH";

    /**
     * LLM finding 是否参与门禁判定。**默认 false**。
     *
     * <p>刻意不让 LLM 输出卡合并：其不确定性太高（离线评估只有 6 个样本、Precision 85.71%），
     * 用它卡合并会让开发者对整个门禁失去信任。</p>
     */
    private boolean gateIncludeLlmFindings = false;

    /** commit status 的 context 名。 */
    private String gateContextName = "CodePilot Review";

    /**
     * 是否启用 LLM finding 的自省闸门（R18）。**默认关闭**。
     *
     * <p>开启后每个文件会多一次模型调用——成本上升，而收益尚未量化。
     * 先关着，等 token 指标（R10）能证明它划算再开。</p>
     */
    private boolean reflectionEnabled = false;

    /**
     * 自省分数低于此值即抑制该 finding。
     *
     * <p><b>不要设到 8 以上</b>：PR-Agent 的官方注释明确警告过，
     * 过高的阈值会"clip highly relevant suggestions"——把真问题一起剪掉。</p>
     */
    private int suggestionsScoreThreshold = 5;

    /**
     * 自省调用失败或输出无法解析时给的分数。**默认 0，即失败即丢弃**。
     *
     * <p>这与 PR-Agent 取 7（高于阈值，等于失败时保留）<b>刻意相反</b>：
     * 我们的离线评估只有 6 个样本，失败时保留等于放行未经复核的内容。宁可漏报。</p>
     */
    private int scoreOnReflectionFailure = 0;

    /** 单个文件最多自省多少条 finding，用于控制成本。 */
    private int reflectionMaxFindings = 10;

    private int maxPatchCharsPerFile = 12000;

    private int maxTotalPatchChars = 80000;

    private int maxParallelFiles = 2;

    private int maxRepoContextFiles = 6;

    private int maxRepoContextFileChars = 20000;

    private int maxRepoContextExcerptChars = 900;

    private Duration executionLease = Duration.ofMinutes(15);

    private int outboxBatchSize = 20;

    private Duration outboxLockTimeout = Duration.ofMinutes(2);

    private Duration outboxConfirmTimeout = Duration.ofSeconds(5);

    private Duration outboxRetryInitialDelay = Duration.ofSeconds(1);

    private Duration outboxRetryMaxDelay = Duration.ofMinutes(1);

    private int outboxMaxAttempts = 10;

    private int publicationMaxAttempts = 5;
}
