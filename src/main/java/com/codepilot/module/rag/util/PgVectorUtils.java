package com.codepilot.module.rag.util;

import com.codepilot.common.exception.BusinessException;

import java.util.List;
import java.util.stream.Collectors;

public final class PgVectorUtils {

    private PgVectorUtils() {
    }

    public static String toVectorString(List<Float> vector) {
        if (vector == null || vector.isEmpty()) {
            throw new BusinessException("embedding vector must not be empty");
        }
        return vector.stream()
                .map(value -> {
                    if (value == null) {
                        throw new BusinessException("embedding vector must not contain null values");
                    }
                    return Float.toString(value);
                })
                .collect(Collectors.joining(",", "[", "]"));
    }
}
