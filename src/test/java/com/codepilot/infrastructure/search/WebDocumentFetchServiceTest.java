package com.codepilot.infrastructure.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebDocumentFetchServiceTest {

    @Test
    void shouldRemainDisabledByDefault() {
        WebDocumentFetchService service = new WebDocumentFetchService(new SearxngProperties());

        assertThat(service.fetch("https://docs.spring.io/reference"))
                .contains("disabled");
    }

    @Test
    void shouldRejectAllowlistedHostnameResolvingToPrivateAddress() {
        SearxngProperties properties = new SearxngProperties();
        properties.setDocumentFetchEnabled(true);
        properties.setAllowedDomains(List.of("localhost"));
        WebDocumentFetchService service = new WebDocumentFetchService(properties);

        assertThatThrownBy(() -> service.fetch("https://localhost/reference"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-public");
    }
}
