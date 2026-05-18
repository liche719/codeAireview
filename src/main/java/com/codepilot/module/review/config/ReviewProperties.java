package com.codepilot.module.review.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "codepilot.review")
public class ReviewProperties {

    private int maxFilesPerTask = 30;

    private int maxPatchCharsPerFile = 12000;

    private int maxTotalPatchChars = 80000;
}
