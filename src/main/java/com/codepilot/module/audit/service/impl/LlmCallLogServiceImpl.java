package com.codepilot.module.audit.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.codepilot.module.audit.entity.LlmCallLog;
import com.codepilot.module.audit.mapper.LlmCallLogMapper;
import com.codepilot.module.audit.service.LlmCallLogService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class LlmCallLogServiceImpl extends ServiceImpl<LlmCallLogMapper, LlmCallLog> implements LlmCallLogService {

    @Override
    public int deleteExpired(int retentionDays) {
        if (retentionDays <= 0) {
            return 0;
        }
        return baseMapper.deleteCreatedBefore(LocalDateTime.now().minusDays(retentionDays));
    }
}
