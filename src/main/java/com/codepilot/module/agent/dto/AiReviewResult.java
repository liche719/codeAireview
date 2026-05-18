package com.codepilot.module.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiReviewResult {

    private List<AiReviewIssue> issues = new ArrayList<>();

    private String summary;

    public static AiReviewResult empty() {
        return new AiReviewResult(new ArrayList<>(), null);
    }
}
