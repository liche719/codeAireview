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
