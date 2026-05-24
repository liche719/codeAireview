package com.codepilot.module.eval;

import com.codepilot.module.tool.dto.ToolCheckResult;
import com.codepilot.module.tool.impl.SecretScanTool;
import com.codepilot.module.tool.impl.SqlRiskTool;
import com.codepilot.module.tool.impl.TestSuggestionTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicReviewEvalTest {

    private static final double MIN_PRECISION = 0.75;

    private static final double MIN_RECALL = 1.0;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final SqlRiskTool sqlRiskTool = new SqlRiskTool();

    private final SecretScanTool secretScanTool = new SecretScanTool();

    private final TestSuggestionTool testSuggestionTool = new TestSuggestionTool();

    @Test
    void shouldPassDeterministicGoldenCases() throws IOException {
        List<DeterministicReviewCase> cases = loadCases();
        EvalMetrics metrics = EvalMetrics.empty();

        for (DeterministicReviewCase reviewCase : cases) {
            List<ToolCheckResult> results = runTools(reviewCase);
            Set<String> actualIssueTypes = results.stream()
                    .map(ToolCheckResult::getIssueType)
                    .collect(Collectors.toSet());
            metrics = metrics.add(reviewCase.name(), reviewCase.expectedIssueTypes(), actualIssueTypes);

            assertThat(actualIssueTypes)
                    .as("case %s issue types", reviewCase.name())
                    .containsAll(reviewCase.expectedIssueTypes());

            for (Map.Entry<String, String> expectation : reviewCase.minimumSeverityByIssueType().entrySet()) {
                assertThat(highestSeverity(results, expectation.getKey()))
                        .as("case %s severity for %s", reviewCase.name(), expectation.getKey())
                        .isLessThanOrEqualTo(severityRank(expectation.getValue()));
            }

            if (reviewCase.expectedIssueTypes().isEmpty()) {
                assertThat(results)
                        .as("case %s should not emit deterministic findings", reviewCase.name())
                        .isEmpty();
            }
        }

        assertThat(metrics.precision())
                .as("deterministic review precision, false positives=%s", metrics.falsePositives())
                .isGreaterThanOrEqualTo(MIN_PRECISION);
        assertThat(metrics.recall())
                .as("deterministic review recall, false negatives=%s", metrics.falseNegatives())
                .isGreaterThanOrEqualTo(MIN_RECALL);
    }

    private List<DeterministicReviewCase> loadCases() throws IOException {
        try (InputStream inputStream = getClass().getResourceAsStream("/eval/deterministic-review-cases.json")) {
            assertThat(inputStream).as("eval fixture should exist").isNotNull();
            return objectMapper.readValue(inputStream, new TypeReference<>() {
            });
        }
    }

    private List<ToolCheckResult> runTools(DeterministicReviewCase reviewCase) {
        String allChangedFilesText = String.join("\n", reviewCase.allChangedFiles());
        List<ToolCheckResult> results = new ArrayList<>();
        results.addAll(sqlRiskTool.checkSqlRisk(reviewCase.filePath(), reviewCase.patch()));
        results.addAll(secretScanTool.scanSecrets(reviewCase.filePath(), reviewCase.patch()));
        results.addAll(testSuggestionTool.suggestTests(reviewCase.filePath(), reviewCase.patch(), allChangedFilesText));
        return results;
    }

    private int highestSeverity(List<ToolCheckResult> results, String issueType) {
        return results.stream()
                .filter(result -> issueType.equals(result.getIssueType()))
                .map(ToolCheckResult::getSeverity)
                .map(this::severityRank)
                .min(Comparator.naturalOrder())
                .orElseThrow(() -> new AssertionError("missing issue type " + issueType));
    }

    private int severityRank(String severity) {
        return switch (severity == null ? "" : severity.trim().toUpperCase()) {
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            case "LOW" -> 3;
            default -> 4;
        };
    }

    private record EvalMetrics(
            int truePositives,
            int falsePositiveCount,
            int falseNegativeCount,
            Set<String> falsePositives,
            Set<String> falseNegatives
    ) {

        private static EvalMetrics empty() {
            return new EvalMetrics(0, 0, 0, new LinkedHashSet<>(), new LinkedHashSet<>());
        }

        private EvalMetrics add(String caseName, Set<String> expectedIssueTypes, Set<String> actualIssueTypes) {
            Set<String> expectedLabels = labels(caseName, expectedIssueTypes);
            Set<String> actualLabels = labels(caseName, actualIssueTypes);

            Set<String> missed = difference(expectedLabels, actualLabels);
            Set<String> unexpected = difference(actualLabels, expectedLabels);
            Set<String> matched = new LinkedHashSet<>(expectedLabels);
            matched.retainAll(actualLabels);

            Set<String> allFalsePositives = new LinkedHashSet<>(falsePositives);
            allFalsePositives.addAll(unexpected);
            Set<String> allFalseNegatives = new LinkedHashSet<>(falseNegatives);
            allFalseNegatives.addAll(missed);

            return new EvalMetrics(
                    truePositives + matched.size(),
                    falsePositiveCount + unexpected.size(),
                    falseNegativeCount + missed.size(),
                    allFalsePositives,
                    allFalseNegatives
            );
        }

        private double precision() {
            int denominator = truePositives + falsePositiveCount;
            return denominator == 0 ? 1.0 : (double) truePositives / denominator;
        }

        private double recall() {
            int denominator = truePositives + falseNegativeCount;
            return denominator == 0 ? 1.0 : (double) truePositives / denominator;
        }

        private static Set<String> labels(String caseName, Set<String> issueTypes) {
            return issueTypes.stream()
                    .map(issueType -> caseName + "::" + issueType)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        private static Set<String> difference(Set<String> left, Set<String> right) {
            Set<String> difference = new LinkedHashSet<>(left);
            difference.removeAll(right);
            return difference;
        }
    }

    private record DeterministicReviewCase(
            String name,
            String filePath,
            String patch,
            List<String> allChangedFiles,
            Set<String> expectedIssueTypes,
            Map<String, String> minimumSeverityByIssueType
    ) {
    }
}
