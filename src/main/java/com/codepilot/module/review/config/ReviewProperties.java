package com.codepilot.module.review.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

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
