package com.codepilot.module.review.context;

import com.codepilot.module.review.config.ReviewProperties;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewTask;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RepoSourceExcerptExtractorTest {

    @Test
    void shouldFetchBoundedRepositorySourceExcerptsFromPullRequestHead() {
        ReviewProperties properties = new ReviewProperties();
        properties.setMaxRepoContextFiles(1);
        properties.setMaxRepoContextFileChars(200);
        properties.setMaxRepoContextExcerptChars(120);
        MapBackedProvider provider = new MapBackedProvider(Map.of(
                "liche719/codeAireview/src/main/java/com/example/AuthService.java@abc123",
                """
                        package com.example;

                        class AuthService {
                            String login(String token) {
                                return token;
                            }
                        }
                        """
        ));
        RepoSourceExcerptExtractor extractor = new RepoSourceExcerptExtractor(provider, properties);

        List<ReviewContext.RepoSourceExcerpt> excerpts = extractor.repoSourceExcerpts(
                task(),
                List.of(reviewFile(
                        "src/main/java/com/example/AuthController.java",
                        """
                                @@ -1,2 +1,3 @@
                                 package com.example;
                                +import com.example.AuthService;
                                +class AuthController {}
                                """
                )),
                List.of(new ReviewContext.SemanticFileContext(
                        "src/main/java/com/example/AuthController.java",
                        "java",
                        "com.example",
                        List.of("AuthController"),
                        List.of(),
                        List.of(),
                        List.of("com.example.AuthService"),
                        List.of()
                )),
                List.of()
        );

        assertThat(excerpts)
                .singleElement()
                .satisfies(excerpt -> {
                    assertThat(excerpt.sourceFile()).isEqualTo("src/main/java/com/example/AuthController.java");
                    assertThat(excerpt.relatedFile()).isEqualTo("src/main/java/com/example/AuthService.java");
                    assertThat(excerpt.reason()).contains("IMPORT_SOURCE");
                    assertThat(excerpt.excerpt()).contains("class AuthService");
                    assertThat(excerpt.truncated()).isFalse();
                });
        assertThat(provider.requests())
                .containsExactly("liche719/codeAireview/src/main/java/com/example/AuthService.java@abc123");
    }

    @Test
    void shouldRedactSecretsAndTruncateRepositorySourceExcerpts() {
        ReviewProperties properties = new ReviewProperties();
        properties.setMaxRepoContextFiles(1);
        properties.setMaxRepoContextFileChars(120);
        properties.setMaxRepoContextExcerptChars(80);
        RepoSourceExcerptExtractor extractor = new RepoSourceExcerptExtractor(
                new MapBackedProvider(Map.of(
                        "liche719/codeAireview/src/test/java/com/example/AuthServiceTest.java@abc123",
                        """
                                class AuthServiceTest {
                                    String token = "ghp_abcdefghijklmnopqrstuvwxyz";
                                    void shouldLogin() {}
                                    void shouldLogout() {}
                                }
                                """
                )),
                properties
        );

        List<ReviewContext.RepoSourceExcerpt> excerpts = extractor.repoSourceExcerpts(
                task(),
                List.of(reviewFile("src/main/java/com/example/AuthService.java", "+class AuthService {}")),
                List.of(),
                List.of()
        );

        assertThat(excerpts)
                .singleElement()
                .satisfies(excerpt -> {
                    assertThat(excerpt.relatedFile()).isEqualTo("src/test/java/com/example/AuthServiceTest.java");
                    assertThat(excerpt.excerpt()).contains("[REDACTED]");
                    assertThat(excerpt.excerpt()).doesNotContain("ghp_abcdefghijklmnopqrstuvwxyz");
                    assertThat(excerpt.excerpt().length()).isLessThanOrEqualTo(80);
                    assertThat(excerpt.truncated()).isTrue();
                });
    }

    @Test
    void shouldSkipUnsafeOrExternalImportCandidates() {
        MapBackedProvider provider = new MapBackedProvider(Map.of(
                "liche719/codeAireview/src/main/java/java/util/List.java@abc123", "class List {}"
        ));
        RepoSourceExcerptExtractor extractor = new RepoSourceExcerptExtractor(provider, new ReviewProperties());

        List<ReviewContext.RepoSourceExcerpt> excerpts = extractor.repoSourceExcerpts(
                task(),
                List.of(reviewFile(
                        "internal/AuthController.java",
                        "+import java.util.List;\n+import ../secrets.Token;"
                )),
                List.of(new ReviewContext.SemanticFileContext(
                        "internal/AuthController.java",
                        "java",
                        "com.example",
                        List.of("AuthController"),
                        List.of(),
                        List.of(),
                        List.of("java.util.List", "../secrets.Token"),
                        List.of()
                )),
                List.of()
        );

        assertThat(excerpts).isEmpty();
        assertThat(provider.requests()).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenPullRequestHeadRefIsMissing() {
        RepoSourceExcerptExtractor extractor = new RepoSourceExcerptExtractor(
                new MapBackedProvider(Map.of()),
                new ReviewProperties()
        );
        ReviewTask task = task();
        task.setHeadSha(null);

        List<ReviewContext.RepoSourceExcerpt> excerpts = extractor.repoSourceExcerpts(
                task,
                List.of(reviewFile("src/main/java/com/example/AuthService.java", "+class AuthService {}")),
                List.of(),
                List.of()
        );

        assertThat(excerpts).isEmpty();
    }

    private static ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(7L);
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        task.setHeadSha("abc123");
        return task;
    }

    private static ReviewFile reviewFile(String path, String patch) {
        ReviewFile reviewFile = new ReviewFile();
        reviewFile.setFilePath(path);
        reviewFile.setPatch(patch);
        reviewFile.setSkipped(false);
        return reviewFile;
    }

    private static class MapBackedProvider implements RepoSourceExcerptProvider {

        private final Map<String, String> contents;

        private final Map<String, Integer> requests = new LinkedHashMap<>();

        private MapBackedProvider(Map<String, String> contents) {
            this.contents = contents;
        }

        @Override
        public String getFileContent(String owner, String repo, String path, String ref) {
            String key = owner + "/" + repo + "/" + path + "@" + ref;
            requests.put(key, requests.getOrDefault(key, 0) + 1);
            return contents.getOrDefault(key, "");
        }

        private List<String> requests() {
            return List.copyOf(requests.keySet());
        }
    }
}
