package com.codepilot.module.review.graph;

import com.codepilot.module.agent.dto.AiReviewContext;
import com.codepilot.module.review.classifier.ReviewFileClassifier;
import com.codepilot.module.review.context.ReviewContext;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class RepositoryGraphSnapshotBuilder {

    private static final int GRAPH_FILE_LIMIT = 12;

    private static final int GRAPH_EDGE_LIMIT = 12;

    private static final int GRAPH_FOCUS_FILE_LIMIT = 8;

    private static final int GRAPH_FOCUS_SYMBOL_LIMIT = 12;

    private static final int GRAPH_SYMBOL_LIMIT = 8;

    private static final int GRAPH_IMPORT_LIMIT = 6;

    private static final int GRAPH_ROUTE_LIMIT = 6;

    private static final int GRAPH_METHOD_LIMIT = 8;

    private static final int GRAPH_ANNOTATION_LIMIT = 6;

    private static final int GRAPH_CURRENT_FILE_BONUS = 120;

    private static final int GRAPH_REVIEWABLE_BONUS = 50;

    private static final int GRAPH_EDGE_DEGREE_BONUS = 18;

    private static final int GRAPH_SYMBOL_BONUS = 14;

    private static final int GRAPH_METHOD_BONUS = 8;

    private static final int GRAPH_ANNOTATION_BONUS = 6;

    private static final int GRAPH_IMPORT_BONUS = 5;

    private static final int GRAPH_ROUTE_BONUS = 10;

    private static final int GRAPH_PATCH_SIZE_BONUS = 15;

    private RepositoryGraphSnapshotBuilder() {
    }

    public static RepositoryGraphSnapshot buildReviewContextGraph(
            List<ReviewContext.FileSummary> fileSummaries,
            List<ReviewContext.SemanticFileContext> semanticFileContexts,
            List<ReviewContext.RepoRelationshipHint> repoRelationshipHints,
            String currentFilePath
    ) {
        return build(
                reviewNodeInputs(fileSummaries, semanticFileContexts),
                reviewEdgeInputs(repoRelationshipHints),
                currentFilePath
        );
    }

    public static RepositoryGraphSnapshot buildAiReviewContextGraph(
            List<AiReviewContext.FileSummary> fileSummaries,
            List<AiReviewContext.SemanticFileContext> semanticFileContexts,
            List<AiReviewContext.RepoRelationshipHint> repoRelationshipHints,
            String currentFilePath
    ) {
        return build(
                aiNodeInputs(fileSummaries, semanticFileContexts),
                aiEdgeInputs(repoRelationshipHints),
                currentFilePath
        );
    }

    private static RepositoryGraphSnapshot build(
            List<GraphNodeInput> nodeInputs,
            List<GraphEdgeInput> edgeInputs,
            String currentFilePath
    ) {
        if ((nodeInputs == null || nodeInputs.isEmpty()) && (edgeInputs == null || edgeInputs.isEmpty())) {
            return RepositoryGraphSnapshot.empty();
        }

        String normalizedCurrentFilePath = normalizePath(currentFilePath);
        Map<String, NodeAccumulator> accumulators = new LinkedHashMap<>();
        if (nodeInputs != null) {
            for (GraphNodeInput nodeInput : nodeInputs) {
                if (nodeInput == null || !StringUtils.hasText(nodeInput.filePath())) {
                    continue;
                }
                String normalizedFilePath = normalizePath(nodeInput.filePath());
                accumulators.putIfAbsent(normalizedFilePath, new NodeAccumulator(nodeInput));
            }
        }

        List<RepositoryGraphSnapshot.GraphEdge> edges = new ArrayList<>();
        if (edgeInputs != null) {
            for (GraphEdgeInput edgeInput : edgeInputs) {
                if (edgeInput == null || !StringUtils.hasText(edgeInput.sourceFile()) || !StringUtils.hasText(edgeInput.targetFile())) {
                    continue;
                }
                String source = edgeInput.sourceFile().trim();
                String target = edgeInput.targetFile().trim();
                String normalizedSource = normalizePath(source);
                String normalizedTarget = normalizePath(target);
                if (!accumulators.containsKey(normalizedSource)) {
                    accumulators.put(normalizedSource, new NodeAccumulator(GraphNodeInput.placeholder(source)));
                }
                if (!accumulators.containsKey(normalizedTarget)) {
                    accumulators.put(normalizedTarget, new NodeAccumulator(GraphNodeInput.placeholder(target)));
                }
                accumulators.get(normalizedSource).degree++;
                accumulators.get(normalizedTarget).degree++;
                edges.add(new RepositoryGraphSnapshot.GraphEdge(
                        source,
                        target,
                        edgeInput.type(),
                        edgeInput.reason()
                ));
            }
        }

        List<RepositoryGraphSnapshot.GraphNode> nodes = accumulators.values().stream()
                .map(accumulator -> accumulator.toNode(normalizedCurrentFilePath))
                .sorted(Comparator
                        .comparingInt(RepositoryGraphSnapshot.GraphNode::score).reversed()
                        .thenComparing(Comparator.comparingInt(RepositoryGraphSnapshot.GraphNode::degree).reversed())
                        .thenComparing(node -> normalizePath(node.filePath())))
                .limit(GRAPH_FILE_LIMIT)
                .toList();

        List<RepositoryGraphSnapshot.GraphEdge> orderedEdges = edges.stream()
                .sorted(Comparator
                        .comparingInt((RepositoryGraphSnapshot.GraphEdge edge) -> edgeTouchesCurrentFile(edge, normalizedCurrentFilePath) ? 0 : 1)
                        .thenComparing(RepositoryGraphSnapshot.GraphEdge::type)
                        .thenComparing(RepositoryGraphSnapshot.GraphEdge::sourceFile)
                        .thenComparing(RepositoryGraphSnapshot.GraphEdge::targetFile))
                .limit(GRAPH_EDGE_LIMIT)
                .toList();

        List<String> focusFiles = nodes.stream()
                .sorted(Comparator
                        .comparingInt((RepositoryGraphSnapshot.GraphNode node) -> normalizePath(node.filePath()).equals(normalizedCurrentFilePath) ? 0 : 1)
                        .thenComparing(Comparator.comparingInt(RepositoryGraphSnapshot.GraphNode::score).reversed())
                        .thenComparing(Comparator.comparingInt(RepositoryGraphSnapshot.GraphNode::degree).reversed())
                        .thenComparing(node -> normalizePath(node.filePath())))
                .map(RepositoryGraphSnapshot.GraphNode::filePath)
                .limit(GRAPH_FOCUS_FILE_LIMIT)
                .toList();

        LinkedHashSet<String> focusSymbols = new LinkedHashSet<>();
        for (String focusFile : focusFiles) {
            RepositoryGraphSnapshot.GraphNode node = nodes.stream()
                    .filter(candidate -> normalizePath(candidate.filePath()).equals(normalizePath(focusFile)))
                    .findFirst()
                    .orElse(null);
            if (node == null) {
                continue;
            }
            addFocusSymbols(focusSymbols, node.symbols(), GRAPH_SYMBOL_LIMIT);
            addFocusSymbols(focusSymbols, node.methods(), GRAPH_SYMBOL_LIMIT);
            addFocusSymbols(focusSymbols, node.routes(), GRAPH_SYMBOL_LIMIT);
        }

        return new RepositoryGraphSnapshot(
                nodes,
                orderedEdges,
                focusFiles,
                focusSymbols.stream().limit(GRAPH_FOCUS_SYMBOL_LIMIT).toList()
        );
    }

    private static List<GraphNodeInput> reviewNodeInputs(
            List<ReviewContext.FileSummary> fileSummaries,
            List<ReviewContext.SemanticFileContext> semanticFileContexts
    ) {
        Map<String, ReviewContext.SemanticFileContext> semanticByPath = semanticByPath(semanticFileContexts);
        List<GraphNodeInput> inputs = new ArrayList<>();
        if (fileSummaries == null) {
            return inputs;
        }
        for (ReviewContext.FileSummary fileSummary : fileSummaries) {
            if (fileSummary == null || !StringUtils.hasText(fileSummary.filePath())) {
                continue;
            }
            ReviewContext.SemanticFileContext semanticContext = semanticByPath.get(normalizePath(fileSummary.filePath()));
            inputs.add(new GraphNodeInput(
                    fileSummary.filePath(),
                    semanticContext == null ? languageFromPath(fileSummary.filePath()) : semanticContext.language(),
                    semanticContext == null ? null : semanticContext.packageName(),
                    semanticContext != null && semanticContext.declaredSymbols() != null
                            ? semanticContext.declaredSymbols()
                            : List.of(),
                    semanticContext != null && semanticContext.changedMethods() != null
                            ? semanticContext.changedMethods()
                            : List.of(),
                    semanticContext != null && semanticContext.annotations() != null
                            ? semanticContext.annotations()
                            : List.of(),
                    semanticContext != null && semanticContext.imports() != null
                            ? semanticContext.imports()
                            : List.of(),
                    semanticContext != null && semanticContext.apiRoutes() != null
                            ? semanticContext.apiRoutes()
                            : List.of(),
                    fileSummary.reviewable(),
                    fileSummary.additions(),
                    fileSummary.deletions(),
                    fileSummary.patchChars(),
                    fileSummary.skipReason()
            ));
        }
        return inputs;
    }

    private static List<GraphNodeInput> aiNodeInputs(
            List<AiReviewContext.FileSummary> fileSummaries,
            List<AiReviewContext.SemanticFileContext> semanticFileContexts
    ) {
        Map<String, AiReviewContext.SemanticFileContext> semanticByPath = aiSemanticByPath(semanticFileContexts);
        List<GraphNodeInput> inputs = new ArrayList<>();
        if (fileSummaries == null) {
            return inputs;
        }
        for (AiReviewContext.FileSummary fileSummary : fileSummaries) {
            if (fileSummary == null || !StringUtils.hasText(fileSummary.filePath())) {
                continue;
            }
            AiReviewContext.SemanticFileContext semanticContext = semanticByPath.get(normalizePath(fileSummary.filePath()));
            inputs.add(new GraphNodeInput(
                    fileSummary.filePath(),
                    semanticContext == null ? languageFromPath(fileSummary.filePath()) : semanticContext.language(),
                    semanticContext == null ? null : semanticContext.packageName(),
                    semanticContext != null && semanticContext.declaredSymbols() != null
                            ? semanticContext.declaredSymbols()
                            : List.of(),
                    semanticContext != null && semanticContext.changedMethods() != null
                            ? semanticContext.changedMethods()
                            : List.of(),
                    semanticContext != null && semanticContext.annotations() != null
                            ? semanticContext.annotations()
                            : List.of(),
                    semanticContext != null && semanticContext.imports() != null
                            ? semanticContext.imports()
                            : List.of(),
                    semanticContext != null && semanticContext.apiRoutes() != null
                            ? semanticContext.apiRoutes()
                            : List.of(),
                    fileSummary.reviewable(),
                    fileSummary.additions(),
                    fileSummary.deletions(),
                    fileSummary.patchChars(),
                    fileSummary.skipReason()
            ));
        }
        return inputs;
    }

    private static List<GraphEdgeInput> reviewEdgeInputs(List<ReviewContext.RepoRelationshipHint> repoRelationshipHints) {
        List<GraphEdgeInput> inputs = new ArrayList<>();
        if (repoRelationshipHints == null) {
            return inputs;
        }
        for (ReviewContext.RepoRelationshipHint hint : repoRelationshipHints) {
            if (hint == null) {
                continue;
            }
            inputs.add(new GraphEdgeInput(hint.sourceFile(), hint.targetFile(), hint.type(), hint.reason()));
        }
        return inputs;
    }

    private static List<GraphEdgeInput> aiEdgeInputs(List<AiReviewContext.RepoRelationshipHint> repoRelationshipHints) {
        List<GraphEdgeInput> inputs = new ArrayList<>();
        if (repoRelationshipHints == null) {
            return inputs;
        }
        for (AiReviewContext.RepoRelationshipHint hint : repoRelationshipHints) {
            if (hint == null) {
                continue;
            }
            inputs.add(new GraphEdgeInput(hint.sourceFile(), hint.targetFile(), hint.type(), hint.reason()));
        }
        return inputs;
    }

    private static Map<String, ReviewContext.SemanticFileContext> semanticByPath(
            List<ReviewContext.SemanticFileContext> semanticFileContexts
    ) {
        Map<String, ReviewContext.SemanticFileContext> semanticByPath = new LinkedHashMap<>();
        if (semanticFileContexts == null) {
            return semanticByPath;
        }
        for (ReviewContext.SemanticFileContext semanticContext : semanticFileContexts) {
            if (semanticContext == null || !StringUtils.hasText(semanticContext.filePath())) {
                continue;
            }
            semanticByPath.putIfAbsent(normalizePath(semanticContext.filePath()), semanticContext);
        }
        return semanticByPath;
    }

    private static Map<String, AiReviewContext.SemanticFileContext> aiSemanticByPath(
            List<AiReviewContext.SemanticFileContext> semanticFileContexts
    ) {
        Map<String, AiReviewContext.SemanticFileContext> semanticByPath = new LinkedHashMap<>();
        if (semanticFileContexts == null) {
            return semanticByPath;
        }
        for (AiReviewContext.SemanticFileContext semanticContext : semanticFileContexts) {
            if (semanticContext == null || !StringUtils.hasText(semanticContext.filePath())) {
                continue;
            }
            semanticByPath.putIfAbsent(normalizePath(semanticContext.filePath()), semanticContext);
        }
        return semanticByPath;
    }

    private static void addFocusSymbols(LinkedHashSet<String> focusSymbols, List<String> values, int limit) {
        if (focusSymbols.size() >= limit || values == null || values.isEmpty()) {
            return;
        }
        values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .forEach(value -> {
                    if (focusSymbols.size() < limit) {
                        focusSymbols.add(value);
                    }
                });
    }

    private static boolean edgeTouchesCurrentFile(RepositoryGraphSnapshot.GraphEdge edge, String normalizedCurrentFilePath) {
        if (!StringUtils.hasText(normalizedCurrentFilePath)) {
            return false;
        }
        return normalizePath(edge.sourceFile()).equals(normalizedCurrentFilePath)
                || normalizePath(edge.targetFile()).equals(normalizedCurrentFilePath);
    }

    private static String normalizePath(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
    }

    private static String languageFromPath(String path) {
        String normalized = normalizePath(path);
        if (normalized.endsWith(".java")) {
            return "java";
        }
        if (normalized.endsWith(".kt")) {
            return "kotlin";
        }
        if (normalized.endsWith(".ts") || normalized.endsWith(".tsx")) {
            return "typescript";
        }
        if (normalized.endsWith(".js") || normalized.endsWith(".jsx")) {
            return "javascript";
        }
        if (normalized.endsWith(".py")) {
            return "python";
        }
        if (normalized.endsWith(".sql")) {
            return "sql";
        }
        return "unknown";
    }

    private static String graphKind(String path) {
        if (ReviewFileClassifier.isDatabasePath(path)) {
            return "database";
        }
        if (ReviewFileClassifier.isSecuritySensitivePath(path)) {
            return "security";
        }
        if (ReviewFileClassifier.isPublicApiPath(path)) {
            return "api";
        }
        if (ReviewFileClassifier.isConfigurationPath(path)) {
            return "configuration";
        }
        if (ReviewFileClassifier.isDependencyManifestPath(path)) {
            return "dependency";
        }
        if (ReviewFileClassifier.isTestPath(path)) {
            return "test";
        }
        if (ReviewFileClassifier.isDocumentationPath(path)) {
            return "documentation";
        }
        if (ReviewFileClassifier.isProductionCodePath(path)) {
            return "production";
        }
        return "other";
    }

    private record GraphNodeInput(
            String filePath,
            String language,
            String packageName,
            List<String> symbols,
            List<String> methods,
            List<String> annotations,
            List<String> imports,
            List<String> routes,
            boolean reviewable,
            int additions,
            int deletions,
            int patchChars,
            String skipReason
    ) {
        private static GraphNodeInput placeholder(String filePath) {
            return new GraphNodeInput(
                    filePath,
                    languageFromPath(filePath),
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    true,
                    0,
                    0,
                    0,
                    null
            );
        }
    }

    private record GraphEdgeInput(String sourceFile, String targetFile, String type, String reason) {
    }

    private static final class NodeAccumulator {

        private final GraphNodeInput input;

        private int degree;

        private NodeAccumulator(GraphNodeInput input) {
            this.input = input;
        }

        private RepositoryGraphSnapshot.GraphNode toNode(String normalizedCurrentFilePath) {
            String filePath = input.filePath();
            int score = score(filePath, input, degree, normalizedCurrentFilePath);
            return new RepositoryGraphSnapshot.GraphNode(
                    filePath,
                    graphKind(filePath),
                    input.language(),
                    input.packageName(),
                    input.reviewable(),
                    input.symbols(),
                    input.methods(),
                    input.annotations(),
                    input.imports(),
                    input.routes(),
                    score,
                    degree
            );
        }

        private int score(
                String filePath,
                GraphNodeInput input,
                int degree,
                String normalizedCurrentFilePath
        ) {
            int score = 0;
            if (input.reviewable()) {
                score += GRAPH_REVIEWABLE_BONUS;
            }
            score += Math.min(Math.max(input.additions() + input.deletions(), 0) / 4, GRAPH_PATCH_SIZE_BONUS);
            score += Math.min(Math.max(input.patchChars(), 0) / 600, GRAPH_PATCH_SIZE_BONUS);
            score += Math.min(size(input.symbols()) * GRAPH_SYMBOL_BONUS, GRAPH_SYMBOL_LIMIT * GRAPH_SYMBOL_BONUS);
            score += Math.min(size(input.methods()) * GRAPH_METHOD_BONUS, GRAPH_METHOD_LIMIT * GRAPH_METHOD_BONUS);
            score += Math.min(size(input.annotations()) * GRAPH_ANNOTATION_BONUS, GRAPH_ANNOTATION_LIMIT * GRAPH_ANNOTATION_BONUS);
            score += Math.min(size(input.imports()) * GRAPH_IMPORT_BONUS, GRAPH_IMPORT_LIMIT * GRAPH_IMPORT_BONUS);
            score += Math.min(size(input.routes()) * GRAPH_ROUTE_BONUS, GRAPH_ROUTE_LIMIT * GRAPH_ROUTE_BONUS);
            score += degree * GRAPH_EDGE_DEGREE_BONUS;
            if (normalizePath(filePath).equals(normalizedCurrentFilePath)) {
                score += GRAPH_CURRENT_FILE_BONUS;
            }
            score += switch (graphKind(filePath)) {
                case "security" -> 100;
                case "database" -> 90;
                case "api" -> 80;
                case "configuration" -> 70;
                case "dependency" -> 60;
                case "production" -> 50;
                case "test" -> 35;
                case "documentation" -> 10;
                default -> 0;
            };
            return Math.max(0, score);
        }

        private int size(List<String> values) {
            return values == null ? 0 : values.size();
        }
    }
}
