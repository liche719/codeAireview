package com.codepilot.module.audit.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.codepilot.module.audit.entity.LlmCallLog;

public interface LlmCallLogService extends IService<LlmCallLog> {

    int deleteExpired(int retentionDays);
}
