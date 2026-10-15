package com.codepilot.infrastructure.search;

import com.codepilot.module.command.mapper.AgentWebResourceMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class AgentWebResourceServiceTest {

    @Test
    void shouldRegisterOnlyUrlsReturnedBySearchForCurrentRun() {
        AgentWebResourceMapper mapper = mock(AgentWebResourceMapper.class);
        AgentWebResourceService service = new AgentWebResourceService(mapper);
        String result = "Web search results:\n- title=Docs\n  url=https://docs.spring.io/ref\n  snippet=Official";

        service.registerSearchResults(9L, result);

        verify(mapper).register(eq(9L), any(), eq("https://docs.spring.io/ref"), any());
        when(mapper.existsForRun(eq(9L), any(), eq("https://docs.spring.io/ref"))).thenReturn(1);
        assertThat(service.isRegistered(9L, "https://docs.spring.io/ref")).isTrue();
        assertThat(service.isRegistered(10L, "https://docs.spring.io/ref")).isFalse();
    }
}
