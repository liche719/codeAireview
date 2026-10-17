package com.codepilot.module.review.processor;

import com.codepilot.common.util.PromptInputSanitizer;
import com.codepilot.common.util.SensitiveDataSanitizer;
import com.codepilot.module.agent.service.CodeReviewReflectionAssistant;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewIssue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM finding 的自省闸门（R18）。
 *
 * <h2>为什么需要它</h2>
 * 在此之前，LLM 产出的 finding 只要通过 patch 校验与定位校验就会被发布——
 * <b>未经复核的幻觉与经过推理的结论享受同等待遇</b>。这个类补上"再看一眼"那一道。
 *
 * <h2>三个刻意的取舍</h2>
 * <ol>
 *   <li><b>只自省 LLM finding。</b>确定性规则（SQL 拼接、密钥泄露、workflow 风险）是
 *       可枚举的模式匹配，让模型去"复核"它们既浪费 token，又会削弱"确定性规则可信"的立场。</li>
 *   <li><b>自省失败时丢弃（默认 0 分），而不是保留。</b>这与 PR-Agent 取 7 的做法相反：
 *       我们的离线评估只有 6 个样本，失败时保留等于放行未经复核的内容。宁可漏报。</li>
 *   <li><b>被丢弃的 finding 不删除</b>，只标 {@code SUPPRESS} + 原因，仍落库、仍计入摘要。
 *       沿用 R7 的"抑制必须可审计"原则——否则"模型把真问题丢了"永远无法复盘。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FindingReflectionService {

    /** 抑制原因，写入 {@code review_issue.suppression_reason}，用于事后统计误杀率。 */
    public static final String REASON_BELOW_THRESHOLD = "reflection_below_threshold";

    static final String STATUS_SCORED = "SCORED";

    static final String STATUS_FAILED = "FAILED";

    static final String STATUS_SKIPPED = "SKIPPED";

    private static final Pattern SCORE_PATTERN = Pattern.compile("\"score\"\\s*:\\s*(-?\\d{1,3})");

    private static final int MAX_SCORE = 10;

    private final ObjectProvider<CodeReviewReflectionAssistant> assistantProvider;

    private final ReviewProperties reviewProperties;

    /**
     * 对一批 finding 做自省，并就地修改它们的自省字段与抑制状态。
     *
     * <p>任何异常都只记 warning：这是旁路增强，不该让已经通过的 finding 因为
     * "复核这一步出了问题"而整批消失。</p>
     */
    public List<ReviewIssue> reflect(String filePath, String patch, List<ReviewIssue> issues) {
        if (reviewProperties == null || !reviewProperties.isReflectionEnabled() || issues == null || issues.isEmpty()) {
            return issues == null ? List.of() : issues;
        }
        CodeReviewReflectionAssistant assistant = assistantProvider == null
                ? null : assistantProvider.getIfAvailable();
        if (assistant == null) {
            log.debug("Finding reflection skipped because the reflection assistant is unavailable, filePath={}", filePath);
            return issues;
        }

        int budget = Math.max(0, reviewProperties.getReflectionMaxFindings());
        int reflected = 0;
        for (ReviewIssue issue : issues) {
            if (issue == null) {
                continue;
            }
            if (!isReflectable(issue)) {
                issue.setReflectionStatus(STATUS_SKIPPED);
                continue;
            }
            if (reflected >= budget) {
                // 超出预算的保持 SKIPPED，绝不因为"没配额"就抑制它。
                issue.setReflectionStatus(STATUS_SKIPPED);
                continue;
            }
            reflected++;
            scoreOne(assistant, filePath, patch, issue);
        }
        log.info("Finding reflection completed, filePath={}, reflected={}, threshold={}",
                filePath, reflected, reviewProperties.getSuggestionsScoreThreshold());
        return issues;
    }

    /** 只自省 LLM 产出的 finding；确定性规则不参与。 */
    private boolean isReflectable(ReviewIssue issue) {
        String source = issue.getSource();
        return StringUtils.hasText(source) && "LLM".equalsIgnoreCase(source.trim());
    }

    private void scoreOne(CodeReviewReflectionAssistant assistant, String filePath, String patch, ReviewIssue issue) {
        int score;
        try {
            score = parseScore(callAssistant(assistant, filePath, patch, issue));
        } catch (Exception exception) {
            score = failureScore();
            issue.setReflectionStatus(STATUS_FAILED);
            log.warn("Finding reflection failed, filePath={}, issueType={}, fallbackScore={}, errorType={}, message={}",
                    filePath, issue.getIssueType(), score,
                    exception.getClass().getSimpleName(),
                    SensitiveDataSanitizer.redact(exception.getMessage()));
        }
        if (!STATUS_FAILED.equals(issue.getReflectionStatus())) {
            issue.setReflectionStatus(STATUS_SCORED);
        }
        issue.setReflectionScore(score);
        if (score < Math.max(0, reviewProperties.getSuggestionsScoreThreshold())) {
            suppress(issue);
        }
    }

    private String callAssistant(
            CodeReviewReflectionAssistant assistant,
            String filePath,
            String patch,
            ReviewIssue issue
    ) {
        dev.langchain4j.service.Result<String> result = assistant.score(
                safe(filePath),
                safe(patch),
                safe(issue.getIssueType()),
                safe(issue.getSeverity()),
                safe(issue.getTitle()),
                safe(issue.getDescription()),
                issue.getLineNumber() == null ? "" : String.valueOf(issue.getLineNumber())
        );
        return result == null ? null : result.content();
    }

    /**
     * 从模型输出里取分数。
     *
     * <p>接受"纯数字"与 {@code {"score": N}} 两种形态，并夹到 0–10：
     * 模型偶尔会回 {@code 11} 或 {@code -1}，越界值如果直接参与比较，
     * 会出现"11 分被当成通过"这种荒唐结果。</p>
     *
     * @throws IllegalArgumentException 输出里找不到任何可解析的分数（由调用方按失败处理）
     */
    static int parseScore(String content) {
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("reflection output is empty");
        }
        Matcher matcher = SCORE_PATTERN.matcher(content);
        if (matcher.find()) {
            return clampToRange(Integer.parseInt(matcher.group(1)));
        }
        // 退路：整段就是一个数字。
        String trimmed = content.trim();
        if (trimmed.matches("-?\\d{1,3}")) {
            return clampToRange(Integer.parseInt(trimmed));
        }
        throw new IllegalArgumentException("reflection output does not contain a score");
    }

    private static int clampToRange(int score) {
        return Math.max(0, Math.min(MAX_SCORE, score));
    }

    private int failureScore() {
        return clampToRange(reviewProperties == null ? 0 : reviewProperties.getScoreOnReflectionFailure());
    }

    /**
     * 抑制但保留：已有其他抑制原因时不覆盖（与 R6 的 {@code markSuppressed} 同一策略），
     * 避免把"评分过低"错写成"已被维护者关闭"。
     */
    private void suppress(ReviewIssue issue) {
        if ("SUPPRESS".equalsIgnoreCase(issue.getPublishDecision())
                && StringUtils.hasText(issue.getSuppressionReason())) {
            return;
        }
        issue.setPublishDecision("SUPPRESS");
        issue.setSuppressionReason(REASON_BELOW_THRESHOLD);
    }

    private String safe(String value) {
        return PromptInputSanitizer.escapeUntrustedBlockDelimiters(value == null ? "" : value);
    }
}
