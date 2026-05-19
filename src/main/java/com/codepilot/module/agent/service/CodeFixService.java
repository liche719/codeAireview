package com.codepilot.module.agent.service;

import com.codepilot.module.agent.dto.CodeFixResult;

public interface CodeFixService {

    CodeFixResult generateFix(Long commandTaskId, String issues, String snippets, String limits);
}
