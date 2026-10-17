package com.codepilot.module.review.gate;

import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * 构建门禁判定（R17）。
 *
 * <h2>核心设计：把"产生评论"与"判定失败"彻底解耦</h2>
 * 这是对标四家系统（reviewdog / Danger JS / SonarQube / Semgrep）后最重要的一条结论：
 * 成熟工具默认都<b>不</b>因为"有 finding"就阻塞，且都把这两件事做成独立维度。
 * reviewdog 默认 {@code -fail-level=none}（有 finding 也返回 0）、Danger JS 的
 * {@code --failOnErrors} 默认 false、SonarQube 把质量门注册成独立的 GitHub check。
 *
 * <p>因此本类只回答一个问题："这次该不该阻塞合并"，与"要不要发评论"无关。</p>
 *
 * <h2>只信确定性规则</h2>
 * LLM finding 默认不参与（{@code gate-include-llm-findings=false}）。
 * 用不确定的输出卡别人的合并，代价与收益完全不成比例——一次误判就会让人撤掉整个门禁。
 *
 * <h2>未知 severity 一律按"不阻塞"处理</h2>
 * 认不出的 severity 说明我们不知道它有多严重，此时<b>不阻塞</b>是唯一安全的方向：
 * 误阻塞会挡住别人的工作，而漏阻塞只是回到"没有门禁"的默认状态。
 */
@Component
@RequiredArgsConstructor
public class ReviewGateEvaluator {

    /** GitHub commit status 的取值——注意<b>没有</b> {@code neutral}。 */
    public static final String STATUS_SUCCESS = "success";

    public static final String STATUS_FAILURE = "failure";

    /** 门禁判定结果：本项目的三态，与 commit status 的取值不是一回事（见 {@link #toCommitStatus}）。 */
    public static final String DECISION_SUCCESS = "SUCCESS";

    public static final String DECISION_NEUTRAL = "NEUTRAL";

    public static final String DECISION_FAILURE = "FAILURE";

    private static final int REASON_MAX_CHARS = 240;

    private final ReviewProperties reviewProperties;

    /**
     * @return 判定结果；{@code writeStatus=false} 表示门禁未启用，调用方<b>不应调用任何 status API</b>
     */
    public GateDecision evaluate(ReviewTaskFacts facts) {
        if (reviewProperties == null || !reviewProperties.isGateEnabled()) {
            return new GateDecision(false, DECISION_NEUTRAL, "gate disabled");
        }
        List<ReviewIssue> issues = facts == null || facts.issues() == null ? List.of() : facts.issues();
        if (issues.isEmpty()) {
            return new GateDecision(true, DECISION_SUCCESS, "no findings");
        }

        int threshold = severityRank(reviewProperties.getGateMinSeverity());
        ReviewIssue blocking = null;
        for (ReviewIssue issue : issues) {
            if (issue == null || !participates(issue)) {
                continue;
            }
            if (severityRank(issue.getSeverity()) >= threshold) {
                blocking = issue;
                break;
            }
        }
        if (blocking != null) {
            return new GateDecision(true, DECISION_FAILURE, failureReason(blocking, issues));
        }
        // 有 finding，但没有任何一条达到阈值（例如只有 MEDIUM、阈值是 HIGH）。
        return new GateDecision(true, DECISION_NEUTRAL,
                "no finding reached the " + normalize(reviewProperties.getGateMinSeverity()) + " threshold");
    }

    /**
     * 判定结果 → commit status 取值。
     *
     * <p><b>NEUTRAL 映射为 {@code success}</b>：GitHub 的 commit status 只有
     * {@code error / failure / pending / success} 四种取值，<b>没有 {@code neutral}</b>——
     * {@code neutral} 是 Checks API 的 conclusion。既然本项目的 NEUTRAL 语义就是"不阻塞"，
     * 映射到 {@code success} 是唯一正确的选择；如果哪天改用 Checks API，这里再映射成 {@code neutral}。</p>
     */
    public static String toCommitStatus(String decision) {
        return DECISION_FAILURE.equals(decision) ? STATUS_FAILURE : STATUS_SUCCESS;
    }

    private boolean participates(ReviewIssue issue) {
        boolean fromLlm = "LLM".equalsIgnoreCase(nullToEmpty(issue.getSource()));
        return !fromLlm || (reviewProperties != null && reviewProperties.isGateIncludeLlmFindings());
    }

    private String failureReason(ReviewIssue blocking, List<ReviewIssue> issues) {
        long blockingCount = issues.stream()
                .filter(issue -> issue != null && participates(issue))
                .filter(issue -> severityRank(issue.getSeverity()) >= severityRank(reviewProperties.getGateMinSeverity()))
                .count();
        String reason = blockingCount + " deterministic finding(s) at or above "
                + normalize(reviewProperties.getGateMinSeverity())
                + "; highest: " + normalize(blocking.getSeverity()) + " " + nullToEmpty(blocking.getIssueType());
        return truncate(reason);
    }

    /**
     * severity 排序。认不出的值排在最前（视为最低），因此永远不会单独触发阻塞。
     */
    private static int severityRank(String severity) {
        String normalized = normalize(severity);
        return switch (normalized) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value) {
        return value.length() <= REASON_MAX_CHARS ? value : value.substring(0, REASON_MAX_CHARS - 1) + "…";
    }

    /**
     * 判定所需的事实。
     *
     * @param issues 本次审查产出的全部 finding（含被抑制的——抑制只影响"发不发评论"，不影响门禁事实）
     */
    public record ReviewTaskFacts(List<ReviewIssue> issues) {
    }

    /**
     * @param writeStatus 是否应写 commit status；false 表示门禁未启用
     */
    public record GateDecision(boolean writeStatus, String decision, String reason) {

        public String commitStatus() {
            return toCommitStatus(decision);
        }
    }
}
