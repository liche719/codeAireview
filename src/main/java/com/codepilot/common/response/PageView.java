package com.codepilot.common.response;

import java.util.List;

public record PageView<T>(
        List<T> records,
        long total,
        long size,
        long current,
        long pages
) {

    public PageView {
        records = records == null ? List.of() : List.copyOf(records);
    }
}
