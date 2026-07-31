package com.codepilot.common.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PageViewContractTest {

    @Test
    void shouldExposeOnlyStablePaginationFields() {
        JsonNode json = JsonMapper.builder().build().valueToTree(
                new PageView<>(List.of("task"), 21, 10, 2, 3)
        );

        assertThat(json.propertyStream().map(entry -> entry.getKey()).collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(Set.of("records", "total", "size", "current", "pages"));
        assertThat(json.get("records").get(0).asText()).isEqualTo("task");
        assertThat(json.get("total").asLong()).isEqualTo(21L);
        assertThat(json.get("pages").asLong()).isEqualTo(3L);
    }
}
