package com.codepilot.module.command.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.codepilot.module.command.entity.PrCommandTaskLog;
import com.codepilot.module.command.mapper.PrCommandTaskLogMapper;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
public class PrCommandTaskLogServiceImpl extends ServiceImpl<PrCommandTaskLogMapper, PrCommandTaskLog>
        implements PrCommandTaskLogService {

    private static final int MAX_DETAIL_LENGTH = 4000;

    @Override
    public void record(Long commandTaskId, String step, boolean success, String message, String detail) {
        try {
            PrCommandTaskLog logRecord = new PrCommandTaskLog();
            logRecord.setCommandTaskId(commandTaskId);
            logRecord.setStep(step);
            logRecord.setSuccess(success);
            logRecord.setMessage(message);
            logRecord.setDetail(truncate(detail));
            logRecord.setCreatedAt(LocalDateTime.now());
            save(logRecord);
        } catch (Exception exception) {
            log.warn("Failed to save PR command task log, commandTaskId={}, step={}", commandTaskId, step, exception);
        }
    }

    private String truncate(String content) {
        if (content == null || content.length() <= MAX_DETAIL_LENGTH) {
            return content;
        }
        return content.substring(0, MAX_DETAIL_LENGTH);
    }
}
