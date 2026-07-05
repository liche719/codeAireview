package com.codepilot.module.review.context;

import com.codepilot.module.review.entity.ReviewFile;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class RepoSourceCandidateCollector {

    private final RepoSourceImportCandidateResolver importCandidateResolver =
            new RepoSourceImportCandidateResolver();

    private final RepoSourceTestPairCandidateResolver testPairCandidateResolver =
            new RepoSourceTestPairCandidateResolver();

    List<RepoSourceCandidate> candidates(
            List<ReviewFile> reviewFiles,
            Map<String, ReviewContext.SemanticFileContext> semanticByPath,
            List<ReviewContext.RepoRelationshipHint> repoRelationshipHints
    ) {
        Map<String, RepoSourceCandidate> candidates = new LinkedHashMap<>();
        collectRelationshipCandidates(candidates, repoRelationshipHints);
        collectImportCandidates(candidates, reviewFiles, semanticByPath);
        collectSourceTestCandidates(candidates, reviewFiles);
        return List.copyOf(candidates.values());
    }

    private void collectRelationshipCandidates(
            Map<String, RepoSourceCandidate> candidates,
            List<ReviewContext.RepoRelationshipHint> repoRelationshipHints
    ) {
        if (repoRelationshipHints == null || repoRelationshipHints.isEmpty()) {
            return;
        }
        for (ReviewContext.RepoRelationshipHint hint : repoRelationshipHints) {
            if (hint == null) {
                continue;
            }
            addCandidate(candidates, hint.sourceFile(), hint.targetFile(), hint.type() + ": " + hint.reason());
            addCandidate(candidates, hint.targetFile(), hint.sourceFile(), hint.type() + ": " + hint.reason());
        }
    }

    private void collectImportCandidates(
            Map<String, RepoSourceCandidate> candidates,
            List<ReviewFile> reviewFiles,
            Map<String, ReviewContext.SemanticFileContext> semanticByPath
    ) {
        if (reviewFiles == null || reviewFiles.isEmpty()) {
            return;
        }
        for (ReviewFile reviewFile : reviewFiles) {
            if (reviewFile == null || !StringUtils.hasText(reviewFile.getFilePath())) {
                continue;
            }
            String sourceFile = reviewFile.getFilePath().trim();
            ReviewContext.SemanticFileContext semanticContext =
                    semanticByPath.get(RepoSourcePathUtils.normalizePath(sourceFile));
            if (semanticContext == null || semanticContext.imports().isEmpty()) {
                continue;
            }
            collectImportCandidates(candidates, sourceFile, semanticContext.imports());
        }
    }

    private void collectImportCandidates(
            Map<String, RepoSourceCandidate> candidates,
            String sourceFile,
            List<String> imports
    ) {
        importCandidateResolver.candidates(sourceFile, imports)
                .forEach(candidate -> addCandidate(
                        candidates,
                        candidate.sourceFile(),
                        candidate.relatedFile(),
                        candidate.reason()
                ));
    }

    private void collectSourceTestCandidates(
            Map<String, RepoSourceCandidate> candidates,
            List<ReviewFile> reviewFiles
    ) {
        if (reviewFiles == null || reviewFiles.isEmpty()) {
            return;
        }
        for (ReviewFile reviewFile : reviewFiles) {
            if (reviewFile == null || !StringUtils.hasText(reviewFile.getFilePath())) {
                continue;
            }
            String filePath = reviewFile.getFilePath().trim();
            testPairCandidateResolver.candidates(filePath)
                    .forEach(candidate -> addCandidate(
                            candidates,
                            candidate.sourceFile(),
                            candidate.relatedFile(),
                            candidate.reason()
                    ));
        }
    }

    private void addCandidate(
            Map<String, RepoSourceCandidate> candidates,
            String sourceFile,
            String relatedFile,
            String reason
    ) {
        if (!StringUtils.hasText(sourceFile)
                || !StringUtils.hasText(relatedFile)
                || !StringUtils.hasText(reason)
                || RepoSourcePathUtils.normalizePath(sourceFile).equals(RepoSourcePathUtils.normalizePath(relatedFile))
                || !RepoSourcePathUtils.isSafeRelativePath(relatedFile)
                || !RepoSourcePathUtils.isSupportedSourcePath(relatedFile)) {
            return;
        }
        candidates.putIfAbsent(
                RepoSourcePathUtils.normalizePath(sourceFile) + "\u0000"
                        + RepoSourcePathUtils.normalizePath(relatedFile),
                new RepoSourceCandidate(
                        sourceFile.trim(),
                        RepoSourcePathUtils.normalizeDisplayPath(relatedFile),
                        reason.trim()
                )
        );
    }
}
