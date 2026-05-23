package com.codepilot.module.review.context;

import java.util.List;

public record ReviewContext(List<String> allChangedFiles) {
}
