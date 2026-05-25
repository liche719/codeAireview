package com.codepilot.module.tool.impl;

import com.codepilot.module.tool.dto.ToolCheckRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestSuggestionToolTest {

    private final TestSuggestionTool testSuggestionTool = new TestSuggestionTool();

    @Test
    void shouldSuggestTestWhenServiceChangedWithoutTestFile() {
        var results = testSuggestionTool.suggestTests(
                "src/main/java/com/codepilot/module/review/service/impl/ReviewTaskServiceImpl.java",
                "+public void processTask(Long taskId) {}",
                """
                        src/main/java/com/codepilot/module/review/service/impl/ReviewTaskServiceImpl.java
                        README.md
                        """
        );

        assertThat(results)
                .anySatisfy(result -> assertThat(result.getIssueType()).isEqualTo("TEST_MISSING"));
    }

    @Test
    void shouldNotSuggestTestWhenTestFileChanged() {
        var results = testSuggestionTool.suggestTests(
                "src/main/java/com/codepilot/module/review/service/impl/ReviewTaskServiceImpl.java",
                "+public void processTask(Long taskId) {}",
                """
                        src/main/java/com/codepilot/module/review/service/impl/ReviewTaskServiceImpl.java
                        src/test/java/com/codepilot/module/review/service/ReviewTaskServiceImplTest.java
                        """
        );

        assertThat(results).isEmpty();
    }

    @Test
    void shouldUseChangedFileListWhenRunAsDeterministicRule() {
        var request = ToolCheckRequest.of(
                "src/main/java/com/codepilot/module/review/service/impl/ReviewTaskServiceImpl.java",
                "+public void processTask(Long taskId) {}",
                List.of(
                        "src/main/java/com/codepilot/module/review/service/impl/ReviewTaskServiceImpl.java",
                        "src/test/java/com/codepilot/module/review/service/ReviewTaskServiceImplTest.java"
                ),
                "formatted prompt context without reliable changed file lines"
        );

        var results = testSuggestionTool.check(request);

        assertThat(results).isEmpty();
    }
}
