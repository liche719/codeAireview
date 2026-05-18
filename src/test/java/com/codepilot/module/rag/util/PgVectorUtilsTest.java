package com.codepilot.module.rag.util;

import com.codepilot.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PgVectorUtilsTest {

    @Test
    void shouldConvertFloatListToPgVectorString() {
        String vector = PgVectorUtils.toVectorString(List.of(0.1F, -0.2F, 3.0F));

        assertThat(vector).isEqualTo("[0.1,-0.2,3.0]");
    }

    @Test
    void shouldRejectEmptyVector() {
        assertThatThrownBy(() -> PgVectorUtils.toVectorString(List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("must not be empty");
    }
}
