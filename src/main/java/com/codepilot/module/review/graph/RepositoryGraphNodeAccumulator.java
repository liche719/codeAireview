package com.codepilot.module.review.graph;

import java.util.List;

class RepositoryGraphNodeAccumulator {

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

    private final RepositoryGraphNodeInput input;

    private int degree;

    RepositoryGraphNodeAccumulator(RepositoryGraphNodeInput input) {
        this.input = input;
    }

    void incrementDegree() {
        degree++;
    }

    RepositoryGraphSnapshot.GraphNode toNode(String normalizedCurrentFilePath) {
        String filePath = input.filePath();
        return new RepositoryGraphSnapshot.GraphNode(
                filePath,
                RepositoryGraphPathUtils.graphKind(filePath),
                input.language(),
                input.packageName(),
                input.reviewable(),
                input.symbols(),
                input.methods(),
                input.annotations(),
                input.imports(),
                input.routes(),
                score(filePath, normalizedCurrentFilePath),
                degree
        );
    }

    private int score(String filePath, String normalizedCurrentFilePath) {
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
        if (RepositoryGraphPathUtils.normalizePath(filePath).equals(normalizedCurrentFilePath)) {
            score += GRAPH_CURRENT_FILE_BONUS;
        }
        score += kindScore(filePath);
        return Math.max(0, score);
    }

    private int kindScore(String filePath) {
        return switch (RepositoryGraphPathUtils.graphKind(filePath)) {
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
    }

    private int size(List<String> values) {
        return values == null ? 0 : values.size();
    }
}
