package com.codepilot.module.eval;

import com.codepilot.infrastructure.llm.LlmProperties;
import com.codepilot.module.agent.dto.ReviewRuleContext;
import com.codepilot.module.agent.parser.AiReviewResultParser;
import com.codepilot.module.agent.parser.AiReviewResultSchemaValidator;
import com.codepilot.module.agent.prompt.AiReviewContextFormatter;
import com.codepilot.module.agent.prompt.ReviewPromptBuilder;
import com.codepilot.module.agent.review.DeterministicReviewToolRunner;
import com.codepilot.module.agent.review.ReviewIssueDeduplicator;
import com.codepilot.module.agent.review.ReviewLlmCallLogger;
import com.codepilot.module.agent.review.ReviewLlmClient;
import com.codepilot.module.agent.review.ReviewLlmClientRegistry;
import com.codepilot.module.agent.review.ReviewLlmGate;
import com.codepilot.module.agent.review.ReviewLlmInput;
import com.codepilot.module.agent.review.ReviewLlmInputLimiter;
import com.codepilot.module.agent.review.ReviewLlmReviewer;
import com.codepilot.module.agent.review.ReviewResultMerger;
import com.codepilot.module.agent.review.cache.ReviewLlmCache;
import com.codepilot.module.agent.service.ReviewRagService;
import com.codepilot.module.agent.service.impl.AiReviewServiceImpl;
import com.codepilot.module.audit.entity.LlmCallLog;
import com.codepilot.module.audit.service.LlmCallLogService;
import com.codepilot.module.review.assembler.ReviewIssueAssembler;
import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.context.ReviewContextBuilder;
import com.codepilot.module.review.context.ReviewContextRelationshipExtractor;
import com.codepilot.module.review.context.ReviewContextSignalExtractor;
import com.codepilot.module.review.diff.DiffLineMapper;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.processor.ReviewFileReviewer;
import com.codepilot.module.review.processor.ReviewFindingRanker;
import com.codepilot.module.review.processor.ReviewIssueLocationGuard;
import com.codepilot.module.review.processor.ReviewIssuePatchVerifier;
import com.codepilot.module.tool.impl.SecretScanTool;
import com.codepilot.module.tool.impl.SqlRiskTool;
import com.codepilot.module.tool.impl.TestSuggestionTool;
import com.codepilot.module.tool.rule.DeterministicReviewRule;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiReviewPipelineEvalTest {

    private static final double MIN_PRECISION = 0.75;

    private static final double MIN_RECALL = 0.85;

    private static final double MAX_MUST_NOT_COMMENT_VIOLATION_RATE = 0.0;

    private static final double MAX_PARSE_FAILURE_RATE = 0.20;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldPassOfflineAiReviewPipelineBenchmark() throws Exception {
        List<AiReviewPipelineCase> cases = loadCases();
        assertThat(cases).as("pipeline eval fixture should include labeled scenarios").hasSizeGreaterThanOrEqualTo(6);

        EvalMetrics metrics = EvalMetrics.empty();
        for (AiReviewPipelineCase reviewCase : cases) {
            EvalRunResult result = runCase(reviewCase);
            metrics = metrics.add(reviewCase, result);

            assertExpectedFindings(reviewCase, result.issues());
            assertMustNotFindings(reviewCase, result.issues());
            if (reviewCase.expectParseFailure()) {
                assertThat(result.parseFailureCount())
                        .as("case %s should record a parser/pipeline failure", reviewCase.name())
                        .isGreaterThan(0);
            }
        }

        assertThat(metrics.precision())
                .as("precision=%s, false positives=%s, report=%s",
                        metrics.precision(),
                        metrics.falsePositives(),
                        metrics.summary())
                .isGreaterThanOrEqualTo(MIN_PRECISION);
        assertThat(metrics.recall())
                .as("recall=%s, false negatives=%s, report=%s",
                        metrics.recall(),
                        metrics.falseNegatives(),
                        metrics.summary())
                .isGreaterThanOrEqualTo(MIN_RECALL);
        assertThat(metrics.mustNotCommentViolationRate())
                .as("must-not-comment violation rate, violations=%s, report=%s",
                        metrics.mustNotCommentViolations(),
                        metrics.summary())
                .isLessThanOrEqualTo(MAX_MUST_NOT_COMMENT_VIOLATION_RATE);
        assertThat(metrics.parseFailureRate())
                .as("parse failure rate, report=%s", metrics.summary())
                .isLessThanOrEqualTo(MAX_PARSE_FAILURE_RATE);
        assertThat(metrics.averageLatencyMs())
                .as("average latency should be measured by offline replay")
                .isGreaterThanOrEqualTo(0.0);
        assertThat(metrics.estimatedPromptTokens())
                .as("token/cost proxy should be collected for baseline comparison")
                .isGreaterThan(0);
    }

    private List<AiReviewPipelineCase> loadCases() throws IOException {
        try (InputStream inputStream = getClass().getResourceAsStream("/eval/ai-review-pipeline-cases.json")) {
            assertThat(inputStream).as("pipeline eval fixture should exist").isNotNull();
            return objectMapper.readerForListOf(AiReviewPipelineCase.class).readValue(inputStream);
        }
    }

    private EvalRunResult runCase(AiReviewPipelineCase reviewCase) {
        FakeReviewLlmClient llmClient = new FakeReviewLlmClient(reviewCase.llmResponses());
        List<LlmCallLog> callLogs = new ArrayList<>();
        LlmCallLogService callLogService = mock(LlmCallLogService.class);
        when(callLogService.save(any(LlmCallLog.class))).thenAnswer(invocation -> {
            callLogs.add(invocation.getArgument(0));
            return true;
        });
        ReviewFileReviewer reviewer = reviewer(llmClient, callLogService);

        List<ReviewIssue> issues = reviewer.review(100L, reviewFiles(reviewCase.files()));
        long parseFailures = issues.stream()
                .filter(issue -> "AI_REVIEW_FAILED".equals(issue.getIssueType()))
                .count();
        long promptChars = llmClient.inputs().stream()
                .mapToLong(AiReviewPipelineEvalTest::promptChars)
                .sum();
        long latencyMs = callLogs.stream()
                .map(LlmCallLog::getCostTimeMs)
                .filter(value -> value != null)
                .mapToLong(Long::longValue)
                .sum();
        return new EvalRunResult(
                issues,
                parseFailures,
                callLogs.size(),
                latencyMs,
                promptChars,
                estimatedTokens(promptChars)
        );
    }

    private ReviewFileReviewer reviewer(
            FakeReviewLlmClient llmClient,
            LlmCallLogService callLogService
    ) {
        LlmProperties llmProperties = new LlmProperties();
        llmProperties.setEnabled(true);
        llmProperties.setApiKey("offline-eval-key");
        llmProperties.setProvider("offline-eval");
        llmProperties.setModel("offline-replay");
        llmProperties.setReviewCacheEnabled(false);

        ReviewIssueDeduplicator deduplicator = new ReviewIssueDeduplicator();
        List<DeterministicReviewRule> deterministicRules = List.of(
                new SecretScanTool(),
                new SqlRiskTool(),
                new TestSuggestionTool()
        );
        ReviewLlmInputLimiter inputLimiter = new ReviewLlmInputLimiter(llmProperties);
        ReviewLlmReviewer llmReviewer = new ReviewLlmReviewer(
                new ReviewLlmClientRegistry(llmProperties, singleClientProvider(llmClient)),
                new AiReviewResultParser(objectMapper, new AiReviewResultSchemaValidator()),
                noRulesRag(),
                new ReviewPromptBuilder(),
                inputLimiter,
                new ReviewLlmCallLogger(llmProperties, callLogService),
                disabledCache()
        );
        AiReviewServiceImpl aiReviewService = new AiReviewServiceImpl(
                new DeterministicReviewToolRunner(deterministicRules, deduplicator),
                new AiReviewContextFormatter(),
                new ReviewLlmGate(llmProperties),
                inputLimiter,
                llmReviewer,
                new ReviewResultMerger(deduplicator)
        );

        ReviewProperties reviewProperties = new ReviewProperties();
        reviewProperties.setMaxParallelFiles(1);
        DiffLineMapper diffLineMapper = new DiffLineMapper();
        return new ReviewFileReviewer(
                aiReviewService,
                new ReviewIssueAssembler(),
                new ReviewIssueLocationGuard(diffLineMapper),
                new ReviewIssuePatchVerifier(diffLineMapper),
                new ReviewContextBuilder(new ReviewContextSignalExtractor(), new ReviewContextRelationshipExtractor()),
                new ReviewFindingRanker(),
                reviewProperties
        );
    }

    private void assertExpectedFindings(AiReviewPipelineCase reviewCase, List<ReviewIssue> issues) {
        for (ExpectedFinding expectedFinding : reviewCase.expectedFindings()) {
            assertThat(issues)
                    .as("case %s should contain expected finding %s", reviewCase.name(), expectedFinding)
                    .anySatisfy(issue -> assertMatchesExpectedFinding(reviewCase, expectedFinding, issue));
        }
    }

    private void assertMatchesExpectedFinding(
            AiReviewPipelineCase reviewCase,
            ExpectedFinding expectedFinding,
            ReviewIssue issue
    ) {
        assertThat(issue.getFilePath())
                .as("case %s expected file", reviewCase.name())
                .isEqualTo(expectedFinding.filePath());
        assertThat(issue.getIssueType())
                .as("case %s expected issue type", reviewCase.name())
                .isEqualTo(expectedFinding.issueType());
        assertThat(severityRank(issue.getSeverity()))
                .as("case %s expected severity at least %s", reviewCase.name(), expectedFinding.minimumSeverity())
                .isLessThanOrEqualTo(severityRank(expectedFinding.minimumSeverity()));
        if (expectedFinding.minimumFinalScore() != null) {
            assertThat(issue.getFinalScore())
                    .as("case %s final score", reviewCase.name())
                    .isGreaterThanOrEqualTo(expectedFinding.minimumFinalScore());
        }
        if (hasText(expectedFinding.expectedSource())) {
            assertThat(issue.getSource())
                    .as("case %s expected source", reviewCase.name())
                    .isEqualTo(expectedFinding.expectedSource());
        }
        if (hasText(expectedFinding.expectedCommentChannel())) {
            assertThat(issue.getCommentChannel())
                    .as("case %s expected comment channel", reviewCase.name())
                    .isEqualTo(expectedFinding.expectedCommentChannel());
        }
        if (hasText(expectedFinding.ruleReferenceContains())) {
            assertThat(issue.getRuleReference())
                    .as("case %s rule reference", reviewCase.name())
                    .contains(expectedFinding.ruleReferenceContains());
        }
        assertThat(issue.getPublishDecision())
                .as("case %s expected publishable finding", reviewCase.name())
                .isEqualTo("PUBLISH");
    }

    private void assertMustNotFindings(AiReviewPipelineCase reviewCase, List<ReviewIssue> issues) {
        for (MustNotFinding mustNotFinding : reviewCase.mustNotFindings()) {
            List<ReviewIssue> violations = issues.stream()
                    .filter(issue -> matchesMustNotFinding(mustNotFinding, issue))
                    .filter(issue -> !"SUPPRESS".equals(issue.getPublishDecision()))
                    .toList();
            assertThat(violations)
                    .as("case %s must not publish finding %s", reviewCase.name(), mustNotFinding)
                    .isEmpty();
        }
    }

    private boolean matchesMustNotFinding(MustNotFinding mustNotFinding, ReviewIssue issue) {
        if (issue == null) {
            return false;
        }
        if (hasText(mustNotFinding.issueType()) && !mustNotFinding.issueType().equals(issue.getIssueType())) {
            return false;
        }
        if (hasText(mustNotFinding.titleContains())) {
            return textContains(issue.getTitle(), mustNotFinding.titleContains());
        }
        return true;
    }

    private static List<ReviewFile> reviewFiles(List<EvalReviewFile> files) {
        return files.stream()
                .map(file -> {
                    ReviewFile reviewFile = new ReviewFile();
                    reviewFile.setFilePath(file.filePath());
                    reviewFile.setPatch(file.patch());
                    reviewFile.setAdditions(file.additions());
                    reviewFile.setDeletions(file.deletions());
                    reviewFile.setChangeType(file.changeType() == null ? "modified" : file.changeType());
                    reviewFile.setSkipped(false);
                    return reviewFile;
                })
                .toList();
    }

    private ObjectProvider<ReviewLlmClient> singleClientProvider(ReviewLlmClient reviewLlmClient) {
        return new ObjectProvider<>() {
            @Override
            public ReviewLlmClient getObject(Object... args) {
                return reviewLlmClient;
            }

            @Override
            public ReviewLlmClient getIfAvailable() {
                return reviewLlmClient;
            }

            @Override
            public ReviewLlmClient getIfUnique() {
                return reviewLlmClient;
            }

            @Override
            public ReviewLlmClient getObject() {
                return reviewLlmClient;
            }

            @Override
            public java.util.stream.Stream<ReviewLlmClient> stream() {
                return java.util.stream.Stream.of(reviewLlmClient);
            }

            @Override
            public java.util.stream.Stream<ReviewLlmClient> orderedStream() {
                return java.util.stream.Stream.of(reviewLlmClient);
            }
        };
    }

    private ReviewRagService noRulesRag() {
        return (filePath, patch) -> List.<ReviewRuleContext>of();
    }

    private ReviewLlmCache disabledCache() {
        return new ReviewLlmCache(null, null, objectMapper, null) {
            @Override
            public Optional<com.codepilot.module.agent.dto.AiReviewResult> find(String providerName, ReviewLlmInput input) {
                return Optional.empty();
            }

            @Override
            public void save(
                    String providerName,
                    ReviewLlmInput input,
                    com.codepilot.module.agent.dto.AiReviewResult result
            ) {
                // Offline replay intentionally bypasses persistence.
            }
        };
    }

    private static long promptChars(ReviewLlmInput input) {
        if (input == null) {
            return 0;
        }
        return length(input.filePath())
                + length(input.patch())
                + length(input.rulesContext())
                + length(input.changedFilesContext());
    }

    private static long estimatedTokens(long promptChars) {
        return Math.max(1, Math.round(promptChars / 4.0D));
    }

    private static int severityRank(String severity) {
        return switch (severity == null ? "" : severity.trim().toUpperCase()) {
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            case "LOW" -> 3;
            default -> 4;
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean textContains(String text, String needle) {
        return text != null
                && needle != null
                && text.toLowerCase().contains(needle.toLowerCase());
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AiReviewPipelineCase(
            String name,
            List<String> scenarioTags,
            List<EvalReviewFile> files,
            Map<String, JsonNode> llmResponses,
            List<ExpectedFinding> expectedFindings,
            List<MustNotFinding> mustNotFindings,
            boolean expectParseFailure
    ) {
        private AiReviewPipelineCase {
            scenarioTags = scenarioTags == null ? List.of() : scenarioTags;
            files = files == null ? List.of() : files;
            llmResponses = llmResponses == null ? Map.of() : llmResponses;
            expectedFindings = expectedFindings == null ? List.of() : expectedFindings;
            mustNotFindings = mustNotFindings == null ? List.of() : mustNotFindings;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EvalReviewFile(
            String filePath,
            String patch,
            String changeType,
            Integer additions,
            Integer deletions
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ExpectedFinding(
            String filePath,
            String issueType,
            String minimumSeverity,
            Integer minimumFinalScore,
            String expectedSource,
            String expectedCommentChannel,
            String ruleReferenceContains
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MustNotFinding(String issueType, String titleContains) {
    }

    private record EvalRunResult(
            List<ReviewIssue> issues,
            long parseFailureCount,
            long llmCallCount,
            long latencyMs,
            long promptChars,
            long estimatedPromptTokens
    ) {
    }

    private record EvalMetrics(
            int truePositives,
            int falsePositiveCount,
            int falseNegativeCount,
            int mustNotCommentViolationCount,
            int mustNotCommentExpectationCount,
            long parseFailureCount,
            long fileCount,
            long llmCallCount,
            long latencyMs,
            long promptChars,
            long estimatedPromptTokens,
            Set<String> falsePositives,
            Set<String> falseNegatives,
            Set<String> mustNotCommentViolations
    ) {

        private static EvalMetrics empty() {
            return new EvalMetrics(
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    new LinkedHashSet<>(),
                    new LinkedHashSet<>(),
                    new LinkedHashSet<>()
            );
        }

        private EvalMetrics add(AiReviewPipelineCase reviewCase, EvalRunResult result) {
            Set<String> expected = labels(reviewCase.name(), reviewCase.expectedFindings(), ExpectedFinding::issueType);
            Set<String> actual = labels(reviewCase.name(), publishableIssues(result.issues()), ReviewIssue::getIssueType);
            Set<String> mustNotViolations = mustNotViolations(reviewCase, result.issues());
            Set<String> missed = difference(expected, actual);
            Set<String> unexpected = difference(actual, expected);
            Set<String> matched = new LinkedHashSet<>(expected);
            matched.retainAll(actual);

            Set<String> allFalsePositives = new LinkedHashSet<>(falsePositives);
            allFalsePositives.addAll(unexpected);
            Set<String> allFalseNegatives = new LinkedHashSet<>(falseNegatives);
            allFalseNegatives.addAll(missed);
            Set<String> allMustNotViolations = new LinkedHashSet<>(mustNotCommentViolations);
            allMustNotViolations.addAll(mustNotViolations);

            return new EvalMetrics(
                    truePositives + matched.size(),
                    falsePositiveCount + unexpected.size(),
                    falseNegativeCount + missed.size(),
                    mustNotCommentViolationCount + mustNotViolations.size(),
                    mustNotCommentExpectationCount + reviewCase.mustNotFindings().size(),
                    parseFailureCount + result.parseFailureCount(),
                    fileCount + reviewCase.files().size(),
                    llmCallCount + result.llmCallCount(),
                    latencyMs + result.latencyMs(),
                    promptChars + result.promptChars(),
                    estimatedPromptTokens + result.estimatedPromptTokens(),
                    allFalsePositives,
                    allFalseNegatives,
                    allMustNotViolations
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

        private double mustNotCommentViolationRate() {
            return mustNotCommentExpectationCount == 0
                    ? 0.0
                    : (double) mustNotCommentViolationCount / mustNotCommentExpectationCount;
        }

        private double parseFailureRate() {
            return fileCount == 0 ? 0.0 : (double) parseFailureCount / fileCount;
        }

        private double averageLatencyMs() {
            return llmCallCount == 0 ? 0.0 : (double) latencyMs / llmCallCount;
        }

        private String summary() {
            return "precision=" + precision()
                    + ", recall=" + recall()
                    + ", mustNotCommentViolationRate=" + mustNotCommentViolationRate()
                    + ", parseFailureRate=" + parseFailureRate()
                    + ", averageLatencyMs=" + averageLatencyMs()
                    + ", estimatedPromptTokens=" + estimatedPromptTokens;
        }

        private List<ReviewIssue> publishableIssues(List<ReviewIssue> issues) {
            return issues.stream()
                    .filter(issue -> issue != null && "PUBLISH".equals(issue.getPublishDecision()))
                    .toList();
        }

        private Set<String> mustNotViolations(AiReviewPipelineCase reviewCase, List<ReviewIssue> issues) {
            Set<String> violations = new LinkedHashSet<>();
            for (MustNotFinding mustNotFinding : reviewCase.mustNotFindings()) {
                issues.stream()
                        .filter(issue -> issue != null && "PUBLISH".equals(issue.getPublishDecision()))
                        .filter(issue -> matches(mustNotFinding, issue))
                        .map(issue -> reviewCase.name() + "::" + issue.getIssueType() + "::" + issue.getTitle())
                        .forEach(violations::add);
            }
            return violations;
        }

        private boolean matches(MustNotFinding mustNotFinding, ReviewIssue issue) {
            if (hasText(mustNotFinding.issueType()) && !mustNotFinding.issueType().equals(issue.getIssueType())) {
                return false;
            }
            return !hasText(mustNotFinding.titleContains()) || textContains(issue.getTitle(), mustNotFinding.titleContains());
        }

        private static <T> Set<String> labels(String caseName, List<T> values, Function<T, String> labelExtractor) {
            return values.stream()
                    .map(labelExtractor)
                    .map(issueType -> caseName + "::" + issueType)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }

        private static Set<String> difference(Set<String> left, Set<String> right) {
            Set<String> difference = new LinkedHashSet<>(left);
            difference.removeAll(right);
            return difference;
        }
    }

    private class FakeReviewLlmClient implements ReviewLlmClient {

        private final Map<String, JsonNode> responses;

        private final List<ReviewLlmInput> inputs = new ArrayList<>();

        private FakeReviewLlmClient(Map<String, JsonNode> responses) {
            this.responses = responses == null ? Map.of() : new LinkedHashMap<>(responses);
        }

        @Override
        public String providerName() {
            return "offline-eval";
        }

        @Override
        public boolean supports(String provider) {
            return "offline-eval".equals(provider);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String review(ReviewLlmInput input) {
            inputs.add(input);
            JsonNode response = responses.get(input.filePath());
            if (response == null) {
                return """
                        {
                          "issues": [],
                          "summary": "offline eval default response"
                        }
                        """;
            }
            if (response.isTextual()) {
                return response.asText();
            }
            return response.toString();
        }

        private List<ReviewLlmInput> inputs() {
            return inputs;
        }
    }

}
