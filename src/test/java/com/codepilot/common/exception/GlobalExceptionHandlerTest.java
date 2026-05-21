package com.codepilot.common.exception;

import com.codepilot.common.response.Result;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldRedactSecretsFromBusinessExceptionResponse() {
        Result<Void> response = handler.handleBusinessException(
                new BusinessException("token=ghp_123456789012345678901234567890123456 is invalid")
        );

        assertThat(response.getMessage())
                .contains("[REDACTED]")
                .doesNotContain("ghp_123456789012345678901234567890123456");
    }

    @Test
    void shouldRedactSecretsFromConstraintViolationResponse() {
        Result<Void> response = handler.handleConstraintViolationException(
                new ConstraintViolationException("password=plain-secret is invalid", java.util.Set.of())
        );

        assertThat(response.getMessage())
                .contains("[REDACTED]")
                .doesNotContain("plain-secret");
    }

    @Test
    void shouldHideUnhandledExceptionDetailsFromResponse() {
        Result<Void> response = handler.handleException(
                new IllegalStateException("token=ghp_123456789012345678901234567890123456")
        );

        assertThat(response.getCode()).isEqualTo(500);
        assertThat(response.getMessage()).isEqualTo("server error");
    }
}
