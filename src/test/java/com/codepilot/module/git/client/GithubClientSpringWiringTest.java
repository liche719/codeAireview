package com.codepilot.module.git.client;

import com.codepilot.module.git.config.GithubProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class GithubClientSpringWiringTest {

    @Test
    void shouldCreateGithubClientAsSpringBean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            assertThat(context.getBean(GithubClient.class)).isNotNull();
        }
    }

    @Configuration
    @ComponentScan(basePackageClasses = GithubClient.class)
    static class TestConfig {

        @Bean
        GithubProperties githubProperties() {
            return new GithubProperties();
        }
    }
}
