package com.codepilot.module.review.processor;

import com.codepilot.module.review.entity.ReviewIssue;

record ReviewFindingRankedCandidate(ReviewIssue issue, int score, String fingerprint, int originalIndex) {
}
