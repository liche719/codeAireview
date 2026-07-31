package com.codepilot.module.review.dto;

import com.codepilot.module.review.entity.ReviewTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewTaskViewContractTest {

    @Test
    void shouldKeepPublicFieldsAndHideExecutionInternals() throws Exception {
        ReviewTask task = new ReviewTask();
        task.setId(1L);
        task.setStatus("RUNNING");
        task.setExecutionToken("internal-token");
        task.setPublishStatus("PENDING");

        JsonNode json = JsonMapper.builder().findAndAddModules().build()
                .valueToTree(ReviewTaskView.from(task));

        assertThat(json.get("id").asLong()).isEqualTo(1L);
        assertThat(json.get("status").asText()).isEqualTo("RUNNING");
        assertThat(json.has("executionToken")).isFalse();
        assertThat(json.has("leaseUntil")).isFalse();
        assertThat(json.has("publishStatus")).isFalse();
    }
}
