package com.codepilot.module.review.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "codepilot.review")
public class ReviewProperties {

    private int maxFilesPerTask = 30;

    private int maxSummaryFindings = 20;

    private int maxPatchCharsPerFile = 12000;

    private int maxTotalPatchChars = 80000;

    private int maxParallelFiles = 2;

    private int maxRepoContextFiles = 6;

    private int maxRepoContextFileChars = 20000;

    private int maxRepoContextExcerptChars = 900;
}
