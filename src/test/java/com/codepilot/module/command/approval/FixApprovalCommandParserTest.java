package com.codepilot.module.command.approval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FixApprovalCommandParserTest {

    private final FixApprovalCommandParser parser = new FixApprovalCommandParser();

    @Test
    void shouldAcceptOnlyExactPublishApprovalForExpectedTask() {
        assertThat(parser.isPublishApproval("批准发布修复 123", 123L)).isTrue();
        assertThat(parser.isPublishApproval("approve fix preview #123!", 123L)).isTrue();
        assertThat(parser.isPublishApproval("请批准发布修复 123", 123L)).isFalse();
        assertThat(parser.isPublishApproval("批准发布修复 124", 123L)).isFalse();
    }

    @Test
    void shouldKeepMergeApprovalSeparateFromPublishApproval() {
        assertThat(parser.isMergeApproval("批准合并修复 456", 456L)).isTrue();
        assertThat(parser.isMergeApproval("approve merge fix 456", 456L)).isTrue();
        assertThat(parser.isMergeApproval("批准发布修复 456", 456L)).isFalse();
    }
}
