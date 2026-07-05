package com.codepilot.module.review.planner;

import com.codepilot.module.review.context.ReviewContext;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

class ReviewPlanCrossFileFocusBuilder {

    List<ReviewPlan.CrossFileFocus> crossFileFocuses(
            List<ReviewContext.RepoRelationshipHint> repoRelationshipHints
    ) {
        if (repoRelationshipHints == null || repoRelationshipHints.isEmpty()) {
            return List.of();
        }
        return repoRelationshipHints.stream()
                .filter(hint -> hint != null)
                .map(hint -> new ReviewPlan.CrossFileFocus(
                        hint.type(),
                        List.of(hint.sourceFile(), hint.targetFile()),
                        crossFileReason(hint),
                        crossFileVerificationHint(hint.type())
                ))
                .toList();
    }

    private String crossFileReason(ReviewContext.RepoRelationshipHint hint) {
        return switch (upper(hint.type())) {
            case "IMPORT_TARGET" -> "Changed files have an importer/importee relationship; validate API compatibility.";
            case "SOURCE_TEST_PAIR" -> "Changed source and matching test should describe the same behavior.";
            case "LAYERED_COMPONENT" -> "Layered components in the same domain changed together; check responsibility drift.";
            case "SAME_PACKAGE" -> "Changed files share package-level coupling.";
            case "SHARED_IMPORT" -> "Changed files depend on shared imports or dependencies.";
            default -> StringUtils.hasText(hint.reason()) ? hint.reason() : "Changed files appear related.";
        };
    }

    private String crossFileVerificationHint(String type) {
        return switch (upper(type)) {
            case "IMPORT_TARGET" -> "Check caller/callee contracts, method signatures, nullability, and exception behavior.";
            case "SOURCE_TEST_PAIR" -> "Check whether tests assert the changed production behavior, not only implementation details.";
            case "LAYERED_COMPONENT" -> "Check whether controller/service/repository responsibilities stayed separated.";
            default -> "Only report cross-file issues grounded in changed files or supplied source excerpts.";
        };
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
