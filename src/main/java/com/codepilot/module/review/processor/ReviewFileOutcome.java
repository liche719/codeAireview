package com.codepilot.module.review.processor;

import com.codepilot.module.review.entity.ReviewIssue;

import java.util.List;

public record ReviewFileOutcome(List<ReviewIssue> issues, Exception failure) {
}
