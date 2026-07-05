package com.codepilot.module.review.graph;

import java.util.List;

record RepositoryGraphNodeInput(
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

    static RepositoryGraphNodeInput placeholder(String filePath) {
        return new RepositoryGraphNodeInput(
                filePath,
                RepositoryGraphPathUtils.languageFromPath(filePath),
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
