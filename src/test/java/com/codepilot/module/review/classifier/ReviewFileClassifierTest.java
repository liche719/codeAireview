package com.codepilot.module.review.classifier;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewFileClassifierTest {

    @Test
    void shouldNormalizePathsConsistently() {
        assertThat(ReviewFileClassifier.normalizePath(" SRC\\Main\\Java\\Demo.java "))
                .isEqualTo("src/main/java/demo.java");
        assertThat(ReviewFileClassifier.fileName("src/main/resources/application.yml"))
                .isEqualTo("application.yml");
    }

    @Test
    void shouldClassifyHighImpactReviewPaths() {
        assertThat(ReviewFileClassifier.isSecuritySensitivePath("src/main/java/com/example/AuthService.java")).isTrue();
        assertThat(ReviewFileClassifier.isDatabasePath("src/main/resources/db/migration/V2__user.sql")).isTrue();
        assertThat(ReviewFileClassifier.isPublicApiPath("src/main/java/com/example/controller/UserController.java")).isTrue();
        assertThat(ReviewFileClassifier.isConfigurationPath(".github/workflows/deploy.yml")).isTrue();
        assertThat(ReviewFileClassifier.isDependencyManifestPath("pom.xml")).isTrue();
    }

    @Test
    void shouldClassifySourceTestAndDocumentationPaths() {
        assertThat(ReviewFileClassifier.isProductionCodePath("src/main/java/UserService.java")).isTrue();
        assertThat(ReviewFileClassifier.isProductionCodePath("src/test/java/UserServiceTest.java")).isFalse();
        assertThat(ReviewFileClassifier.isTestPath("frontend/src/UserCard.spec.tsx")).isTrue();
        assertThat(ReviewFileClassifier.isDocumentationPath("docs/review.md")).isTrue();
    }
}
