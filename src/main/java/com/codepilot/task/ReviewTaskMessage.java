package com.codepilot.task;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewTaskMessage {

    private Long taskId;

    private String traceId;
}

