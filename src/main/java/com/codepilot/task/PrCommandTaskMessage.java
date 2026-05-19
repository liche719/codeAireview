package com.codepilot.task;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrCommandTaskMessage {

    private Long commandTaskId;

    private String traceId;
}
