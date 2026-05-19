package com.codepilot.module.command.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.codepilot.module.command.entity.PrCommandTaskLog;

public interface PrCommandTaskLogService extends IService<PrCommandTaskLog> {

    void record(Long commandTaskId, String step, boolean success, String message, String detail);
}
