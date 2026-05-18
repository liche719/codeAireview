package com.codepilot.module.tool.impl;

import org.junit.jupiter.api.Test;

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
}
