package com.codepilot.module.review.graph;

import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public record RepositoryGraphSnapshot(
        List<GraphNode> nodes,
        List<GraphEdge> edges,
        List<String> focusFiles,
        List<String> focusSymbols
) {

    private static final int NODE_LIMIT = 12;

    private static final int EDGE_LIMIT = 12;

    private static final int FOCUS_FILE_LIMIT = 8;

    private static final int FOCUS_SYMBOL_LIMIT = 12;

    public RepositoryGraphSnapshot {
        nodes = sanitizeNodes(nodes);
        edges = sanitizeEdges(edges);
        focusFiles = sanitizeTextList(focusFiles, FOCUS_FILE_LIMIT);
        focusSymbols = sanitizeTextList(focusSymbols, FOCUS_SYMBOL_LIMIT);
    }

    public static RepositoryGraphSnapshot empty() {
        return new RepositoryGraphSnapshot(List.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return nodes.isEmpty() && edges.isEmpty() && focusFiles.isEmpty() && focusSymbols.isEmpty();
    }

    public Optional<GraphNode> nodeFor(String filePath) {
        String normalized = normalizePath(filePath);
        if (!StringUtils.hasText(normalized)) {
            return Optional.empty();
        }
        return nodes.stream()
                .filter(node -> normalizePath(node.filePath()).equals(normalized))
                .findFirst();
    }

    public int scoreFor(String filePath) {
        return nodeFor(filePath)
                .map(GraphNode::score)
                .orElse(0);
    }

    public int degreeFor(String filePath) {
        return nodeFor(filePath)
                .map(GraphNode::degree)
                .orElse(0);
    }

    public List<GraphEdge> edgesFor(String filePath) {
        String normalized = normalizePath(filePath);
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        return edges.stream()
                .filter(edge -> normalizePath(edge.sourceFile()).equals(normalized)
                        || normalizePath(edge.targetFile()).equals(normalized))
                .toList();
    }

    public List<String> relatedFilesFor(String filePath) {
        String normalized = normalizePath(filePath);
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        LinkedHashSet<String> relatedFiles = new LinkedHashSet<>();
        for (GraphEdge edge : edgesFor(filePath)) {
            if (edge == null) {
                continue;
            }
            if (StringUtils.hasText(edge.sourceFile()) && !normalizePath(edge.sourceFile()).equals(normalized)) {
                relatedFiles.add(edge.sourceFile());
            }
            if (StringUtils.hasText(edge.targetFile()) && !normalizePath(edge.targetFile()).equals(normalized)) {
                relatedFiles.add(edge.targetFile());
            }
        }
        return relatedFiles.stream()
                .limit(FOCUS_FILE_LIMIT)
                .toList();
    }

    private static List<GraphNode> sanitizeNodes(List<GraphNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        return nodes.stream()
                .filter(node -> node != null && StringUtils.hasText(node.filePath()))
                .limit(NODE_LIMIT)
                .toList();
    }

    private static List<GraphEdge> sanitizeEdges(List<GraphEdge> edges) {
        if (edges == null || edges.isEmpty()) {
            return List.of();
        }
        return edges.stream()
                .filter(edge -> edge != null
                        && StringUtils.hasText(edge.sourceFile())
                        && StringUtils.hasText(edge.targetFile())
                        && StringUtils.hasText(edge.type())
                        && StringUtils.hasText(edge.reason()))
                .limit(EDGE_LIMIT)
                .toList();
    }

    private static List<String> sanitizeTextList(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> sanitized = new LinkedHashSet<>();
        values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .forEach(sanitized::add);
        return sanitized.stream()
                .limit(limit)
                .toList();
    }

    private static String normalizePath(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
    }

    public record GraphNode(
            String filePath,
            String kind,
            String language,
            String packageName,
            boolean reviewable,
            List<String> symbols,
            List<String> methods,
            List<String> annotations,
            List<String> imports,
            List<String> routes,
            int score,
            int degree
    ) {
        public GraphNode {
            filePath = singleLine(filePath);
            kind = singleLine(kind);
            language = singleLine(language);
            packageName = singleLine(packageName);
            symbols = sanitizeTextList(symbols, 8);
            methods = sanitizeTextList(methods, 8);
            annotations = sanitizeTextList(annotations, 6);
            imports = sanitizeTextList(imports, 6);
            routes = sanitizeTextList(routes, 6);
            score = Math.max(0, score);
            degree = Math.max(0, degree);
        }

        private static String singleLine(String value) {
            if (!StringUtils.hasText(value)) {
                return "";
            }
            return value.replace('\r', ' ')
                    .replace('\n', ' ')
                    .replace('\t', ' ')
                    .trim();
        }
    }

    public record GraphEdge(
            String sourceFile,
            String targetFile,
            String type,
            String reason
    ) {
        public GraphEdge {
            sourceFile = singleLine(sourceFile);
            targetFile = singleLine(targetFile);
            type = singleLine(type);
            reason = singleLine(reason);
        }

        private static String singleLine(String value) {
            if (!StringUtils.hasText(value)) {
                return "";
            }
            return value.replace('\r', ' ')
                    .replace('\n', ' ')
                    .replace('\t', ' ')
                    .trim();
        }
    }
}
