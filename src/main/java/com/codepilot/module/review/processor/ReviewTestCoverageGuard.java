package com.codepilot.module.review.processor;

import com.codepilot.module.review.classifier.ReviewFileClassifier;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.entity.ReviewFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「这条 PR 明明补了测试，模型还说缺测试」的确定性拦截（2026-10-29）。
 *
 * <p><b>为什么用规则而不是继续写 prompt</b>：真实模型评测里，这条误报在提示词写了两遍之后仍然出现
 * （`no-test-missing-when-test-file-changed`）。它能看见 diff，但看不见"这次 PR 的其它文件里已经加了测试"——
 * 而这件事系统是**完全知道**的：变更文件列表就在手上。
 *
 * <p>所以把它降级成确定性规则：**PR 里存在与目标类同名的测试文件时，丢弃 LLM 报的 TEST_MISSING**。
 * 这也符合项目原有分工——能确定的事交给规则，判断不了的事才交给模型。
 */
public final class ReviewTestCoverageGuard {

    private static final Logger log = LoggerFactory.getLogger(ReviewTestCoverageGuard.class);

    private ReviewTestCoverageGuard() {
    }

    /**
     * @param issues     已排好序的问题列表
     * @param reviewFiles 本次 PR 的变更文件（用来判断"有没有人补测试"）
     * @return 过滤后的列表；顺序保持不变
     */
    public static List<ReviewIssue> suppressUnfoundedTestMissing(List<ReviewIssue> issues, List<ReviewFile> reviewFiles) {
        if (issues == null || issues.isEmpty() || reviewFiles == null || reviewFiles.isEmpty()) {
            return issues == null ? List.of() : issues;
        }
        Set<String> testSubjects = testSubjects(reviewFiles);
        if (testSubjects.isEmpty()) {
            return issues;
        }
        List<ReviewIssue> kept = new ArrayList<>(issues.size());
        int dropped = 0;
        for (ReviewIssue issue : issues) {
            if (isLlmTestMissing(issue) && testSubjects.contains(subjectOf(issue.getFilePath()))) {
                dropped++;
                continue;
            }
            kept.add(issue);
        }
        if (dropped > 0) {
            log.info("Dropped LLM TEST_MISSING issue(s) because the PR already adds tests, droppedCount={}", dropped);
        }
        return kept;
    }

    /** 本次 PR 里"被测试的对象"：BillingServiceTest.java → billingservice */
    private static Set<String> testSubjects(List<ReviewFile> reviewFiles) {
        Set<String> subjects = new LinkedHashSet<>();
        for (ReviewFile file : reviewFiles) {
            String path = file == null ? null : file.getFilePath();
            if (!ReviewFileClassifier.isTestPath(path)) {
                continue;
            }
            String simple = simpleName(path);
            if (simple.endsWith("test")) {
                subjects.add(simple.substring(0, simple.length() - 4));
            }
            if (simple.endsWith("tests")) {
                subjects.add(simple.substring(0, simple.length() - 5));
            }
            if (simple.startsWith("test")) {
                subjects.add(simple.substring(4));
            }
        }
        return subjects;
    }

    private static boolean isLlmTestMissing(ReviewIssue issue) {
        if (issue == null) {
            return false;
        }
        String source = issue.getSource() == null ? "" : issue.getSource().trim().toUpperCase(Locale.ROOT);
        String type = issue.getIssueType() == null ? "" : issue.getIssueType().trim().toUpperCase(Locale.ROOT);
        // 只拦模型报的：确定性工具（TOOL）报的是它自己扫出来的，规则不该越权改
        return "LLM".equals(source) && "TEST_MISSING".equals(type);
    }

    private static String subjectOf(String filePath) {
        return simpleName(filePath);
    }

    private static String simpleName(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return "";
        }
        String name = filePath.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.toLowerCase(Locale.ROOT);
    }
}
