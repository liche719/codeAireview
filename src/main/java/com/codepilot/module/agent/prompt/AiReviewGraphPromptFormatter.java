package com.codepilot.module.agent.prompt;

import com.codepilot.module.agent.dto.AiReviewContext;
import com.codepilot.module.review.graph.RepositoryGraphSnapshot;
import com.codepilot.module.review.graph.RepositoryGraphSnapshotBuilder;
import org.springframework.util.StringUtils;

import java.util.List;

class AiReviewGraphPromptFormatter {

    private static final int GRAPH_NODE_CONTEXT_LIMIT = 6;

    private static final int GRAPH_EDGE_CONTEXT_LIMIT = 6;

    void appendRepositoryGraphSnapshot(
            StringBuilder builder,
            AiReviewContext context,
            String currentFilePath
    ) {
        if (!StringUtils.hasText(currentFilePath)) {
            return;
        }
        RepositoryGraphSnapshot graphSnapshot = RepositoryGraphSnapshotBuilder.buildAiReviewContextGraph(
                context.fileSummaries(),
                context.semanticFileContexts(),
                context.repoRelationshipHints(),
                currentFilePath
        );
        if (graphSnapshot.isEmpty()) {
            return;
        }

        builder.append("\nRepository graph snapshot (symbol-aware, patch-derived, bounded):\n");
        appendGraphList(builder, "focus files", graphSnapshot.focusFiles(), GRAPH_NODE_CONTEXT_LIMIT);
        appendGraphList(builder, "focus symbols", graphSnapshot.focusSymbols(), GRAPH_NODE_CONTEXT_LIMIT);
        appendGraphNodes(builder, graphSnapshot);
        appendGraphEdges(builder, graphSnapshot);
    }

    private void appendGraphNodes(StringBuilder builder, RepositoryGraphSnapshot graphSnapshot) {
        int nodeLimit = Math.min(graphSnapshot.nodes().size(), GRAPH_NODE_CONTEXT_LIMIT);
        if (nodeLimit <= 0) {
            return;
        }
        builder.append("- graph nodes:\n");
        for (int index = 0; index < nodeLimit; index++) {
            RepositoryGraphSnapshot.GraphNode node = graphSnapshot.nodes().get(index);
            builder.append("  - ")
                    .append(singleLine(node.filePath()))
                    .append(" [")
                    .append(singleLine(node.kind()))
                    .append(", language=")
                    .append(singleLine(node.language()))
                    .append(", score=")
                    .append(node.score())
                    .append(", degree=")
                    .append(node.degree())
                    .append("]\n");
            appendIndentedGraphList(builder, "symbols", node.symbols());
            appendIndentedGraphList(builder, "methods", node.methods());
            appendIndentedGraphList(builder, "imports", node.imports());
            appendIndentedGraphList(builder, "routes", node.routes());
        }
        if (graphSnapshot.nodes().size() > GRAPH_NODE_CONTEXT_LIMIT) {
            builder.append("  - ")
                    .append(graphSnapshot.nodes().size() - GRAPH_NODE_CONTEXT_LIMIT)
                    .append(" more graph nodes omitted\n");
        }
    }

    private void appendGraphEdges(StringBuilder builder, RepositoryGraphSnapshot graphSnapshot) {
        int edgeLimit = Math.min(graphSnapshot.edges().size(), GRAPH_EDGE_CONTEXT_LIMIT);
        if (edgeLimit <= 0) {
            return;
        }
        builder.append("- graph edges:\n");
        for (int index = 0; index < edgeLimit; index++) {
            RepositoryGraphSnapshot.GraphEdge edge = graphSnapshot.edges().get(index);
            builder.append("  - ")
                    .append(singleLine(edge.sourceFile()))
                    .append(" -> ")
                    .append(singleLine(edge.targetFile()))
                    .append(" [")
                    .append(singleLine(edge.type()))
                    .append("]: ")
                    .append(singleLine(edge.reason()))
                    .append('\n');
        }
        if (graphSnapshot.edges().size() > GRAPH_EDGE_CONTEXT_LIMIT) {
            builder.append("  - ")
                    .append(graphSnapshot.edges().size() - GRAPH_EDGE_CONTEXT_LIMIT)
                    .append(" more graph edges omitted\n");
        }
    }

    private void appendGraphList(StringBuilder builder, String label, List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return;
        }
        builder.append("- ")
                .append(label)
                .append(": ")
                .append(values.stream()
                        .limit(limit)
                        .map(this::singleLine)
                        .reduce((left, right) -> left + "; " + right)
                        .orElse("N/A"))
                .append('\n');
    }

    private void appendIndentedGraphList(StringBuilder builder, String label, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        builder.append("    - ")
                .append(label)
                .append(": ")
                .append(values.stream()
                        .limit(4)
                        .map(this::singleLine)
                        .reduce((left, right) -> left + ", " + right)
                        .orElse("N/A"))
                .append('\n');
    }

    private String singleLine(String value) {
        if (!StringUtils.hasText(value)) {
            return "N/A";
        }
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .trim();
    }
}
