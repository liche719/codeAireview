package com.codepilot.module.review.graph;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

class RepositoryGraphSnapshotAssembler {

    private static final int GRAPH_FILE_LIMIT = 12;

    private static final int GRAPH_EDGE_LIMIT = 12;

    private static final int GRAPH_FOCUS_FILE_LIMIT = 8;

    private static final int GRAPH_FOCUS_SYMBOL_LIMIT = 12;

    private static final int GRAPH_SYMBOL_LIMIT = 8;

    RepositoryGraphSnapshot build(
            List<RepositoryGraphNodeInput> nodeInputs,
            List<RepositoryGraphEdgeInput> edgeInputs,
            String currentFilePath
    ) {
        if ((nodeInputs == null || nodeInputs.isEmpty()) && (edgeInputs == null || edgeInputs.isEmpty())) {
            return RepositoryGraphSnapshot.empty();
        }

        String normalizedCurrentFilePath = RepositoryGraphPathUtils.normalizePath(currentFilePath);
        Map<String, RepositoryGraphNodeAccumulator> accumulators = nodeAccumulators(nodeInputs);
        List<RepositoryGraphSnapshot.GraphEdge> edges = graphEdges(edgeInputs, accumulators);
        List<RepositoryGraphSnapshot.GraphNode> nodes = orderedNodes(accumulators, normalizedCurrentFilePath);
        List<RepositoryGraphSnapshot.GraphEdge> orderedEdges = orderedEdges(edges, normalizedCurrentFilePath);
        List<String> focusFiles = focusFiles(nodes, normalizedCurrentFilePath);
        List<String> focusSymbols = focusSymbols(nodes, focusFiles);

        return new RepositoryGraphSnapshot(nodes, orderedEdges, focusFiles, focusSymbols);
    }

    private Map<String, RepositoryGraphNodeAccumulator> nodeAccumulators(
            List<RepositoryGraphNodeInput> nodeInputs
    ) {
        Map<String, RepositoryGraphNodeAccumulator> accumulators = new LinkedHashMap<>();
        if (nodeInputs == null) {
            return accumulators;
        }
        for (RepositoryGraphNodeInput nodeInput : nodeInputs) {
            if (nodeInput == null || !StringUtils.hasText(nodeInput.filePath())) {
                continue;
            }
            String normalizedFilePath = RepositoryGraphPathUtils.normalizePath(nodeInput.filePath());
            accumulators.putIfAbsent(normalizedFilePath, new RepositoryGraphNodeAccumulator(nodeInput));
        }
        return accumulators;
    }

    private List<RepositoryGraphSnapshot.GraphEdge> graphEdges(
            List<RepositoryGraphEdgeInput> edgeInputs,
            Map<String, RepositoryGraphNodeAccumulator> accumulators
    ) {
        List<RepositoryGraphSnapshot.GraphEdge> edges = new ArrayList<>();
        if (edgeInputs == null) {
            return edges;
        }
        for (RepositoryGraphEdgeInput edgeInput : edgeInputs) {
            if (edgeInput == null
                    || !StringUtils.hasText(edgeInput.sourceFile())
                    || !StringUtils.hasText(edgeInput.targetFile())) {
                continue;
            }
            addEdge(edgeInput, accumulators, edges);
        }
        return edges;
    }

    private void addEdge(
            RepositoryGraphEdgeInput edgeInput,
            Map<String, RepositoryGraphNodeAccumulator> accumulators,
            List<RepositoryGraphSnapshot.GraphEdge> edges
    ) {
        String source = edgeInput.sourceFile().trim();
        String target = edgeInput.targetFile().trim();
        String normalizedSource = RepositoryGraphPathUtils.normalizePath(source);
        String normalizedTarget = RepositoryGraphPathUtils.normalizePath(target);
        accumulators.putIfAbsent(normalizedSource, new RepositoryGraphNodeAccumulator(RepositoryGraphNodeInput.placeholder(source)));
        accumulators.putIfAbsent(normalizedTarget, new RepositoryGraphNodeAccumulator(RepositoryGraphNodeInput.placeholder(target)));
        accumulators.get(normalizedSource).incrementDegree();
        accumulators.get(normalizedTarget).incrementDegree();
        edges.add(new RepositoryGraphSnapshot.GraphEdge(source, target, edgeInput.type(), edgeInput.reason()));
    }

    private List<RepositoryGraphSnapshot.GraphNode> orderedNodes(
            Map<String, RepositoryGraphNodeAccumulator> accumulators,
            String normalizedCurrentFilePath
    ) {
        return accumulators.values().stream()
                .map(accumulator -> accumulator.toNode(normalizedCurrentFilePath))
                .sorted(Comparator
                        .comparingInt(RepositoryGraphSnapshot.GraphNode::score).reversed()
                        .thenComparing(Comparator.comparingInt(RepositoryGraphSnapshot.GraphNode::degree).reversed())
                        .thenComparing(node -> RepositoryGraphPathUtils.normalizePath(node.filePath())))
                .limit(GRAPH_FILE_LIMIT)
                .toList();
    }

    private List<RepositoryGraphSnapshot.GraphEdge> orderedEdges(
            List<RepositoryGraphSnapshot.GraphEdge> edges,
            String normalizedCurrentFilePath
    ) {
        return edges.stream()
                .sorted(Comparator
                        .comparingInt((RepositoryGraphSnapshot.GraphEdge edge) ->
                                edgeTouchesCurrentFile(edge, normalizedCurrentFilePath) ? 0 : 1)
                        .thenComparing(RepositoryGraphSnapshot.GraphEdge::type)
                        .thenComparing(RepositoryGraphSnapshot.GraphEdge::sourceFile)
                        .thenComparing(RepositoryGraphSnapshot.GraphEdge::targetFile))
                .limit(GRAPH_EDGE_LIMIT)
                .toList();
    }

    private List<String> focusFiles(
            List<RepositoryGraphSnapshot.GraphNode> nodes,
            String normalizedCurrentFilePath
    ) {
        return nodes.stream()
                .sorted(Comparator
                        .comparingInt((RepositoryGraphSnapshot.GraphNode node) ->
                                RepositoryGraphPathUtils.normalizePath(node.filePath()).equals(normalizedCurrentFilePath) ? 0 : 1)
                        .thenComparing(Comparator.comparingInt(RepositoryGraphSnapshot.GraphNode::score).reversed())
                        .thenComparing(Comparator.comparingInt(RepositoryGraphSnapshot.GraphNode::degree).reversed())
                        .thenComparing(node -> RepositoryGraphPathUtils.normalizePath(node.filePath())))
                .map(RepositoryGraphSnapshot.GraphNode::filePath)
                .limit(GRAPH_FOCUS_FILE_LIMIT)
                .toList();
    }

    private List<String> focusSymbols(
            List<RepositoryGraphSnapshot.GraphNode> nodes,
            List<String> focusFiles
    ) {
        LinkedHashSet<String> focusSymbols = new LinkedHashSet<>();
        for (String focusFile : focusFiles) {
            RepositoryGraphSnapshot.GraphNode node = nodeByPath(nodes, focusFile);
            if (node == null) {
                continue;
            }
            addFocusSymbols(focusSymbols, node.symbols(), GRAPH_SYMBOL_LIMIT);
            addFocusSymbols(focusSymbols, node.methods(), GRAPH_SYMBOL_LIMIT);
            addFocusSymbols(focusSymbols, node.routes(), GRAPH_SYMBOL_LIMIT);
        }
        return focusSymbols.stream().limit(GRAPH_FOCUS_SYMBOL_LIMIT).toList();
    }

    private RepositoryGraphSnapshot.GraphNode nodeByPath(
            List<RepositoryGraphSnapshot.GraphNode> nodes,
            String filePath
    ) {
        String normalizedFilePath = RepositoryGraphPathUtils.normalizePath(filePath);
        return nodes.stream()
                .filter(candidate -> RepositoryGraphPathUtils.normalizePath(candidate.filePath()).equals(normalizedFilePath))
                .findFirst()
                .orElse(null);
    }

    private void addFocusSymbols(LinkedHashSet<String> focusSymbols, List<String> values, int limit) {
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

    private boolean edgeTouchesCurrentFile(
            RepositoryGraphSnapshot.GraphEdge edge,
            String normalizedCurrentFilePath
    ) {
        if (!StringUtils.hasText(normalizedCurrentFilePath)) {
            return false;
        }
        return RepositoryGraphPathUtils.normalizePath(edge.sourceFile()).equals(normalizedCurrentFilePath)
                || RepositoryGraphPathUtils.normalizePath(edge.targetFile()).equals(normalizedCurrentFilePath);
    }
}
