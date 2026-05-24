package com.codepilot.module.agent.prompt;

import com.codepilot.module.agent.dto.ReviewRuleContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewPromptBuilderTest {

    private final ReviewPromptBuilder reviewPromptBuilder = new ReviewPromptBuilder();

    @Test
    void shouldMentionNoRulesWhenRulesAreEmpty() {
        String rulesContext = reviewPromptBuilder.buildRulesContext(List.of());

        assertThat(rulesContext).contains("未检索到相关团队规范");
    }

    @Test
    void shouldIncludeRuleContentWhenRulesArePresent() {
        ReviewRuleContext context = new ReviewRuleContext();
        context.setType("SQL_RULE");
        context.setContent("禁止字符串拼接 SQL，应使用参数绑定。");
        context.setDistance(0.12D);

        String rulesContext = reviewPromptBuilder.buildRulesContext(List.of(context));

        assertThat(rulesContext).contains("SQL_RULE");
        assertThat(rulesContext).contains("禁止字符串拼接 SQL");
        assertThat(rulesContext).contains("UNTRUSTED_RULE_1");
        assertThat(rulesContext).contains("审查数据");
        assertThat(rulesContext).contains("距离：0.1200");
    }
}
