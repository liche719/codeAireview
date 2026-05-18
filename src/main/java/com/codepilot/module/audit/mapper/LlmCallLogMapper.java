package com.codepilot.module.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codepilot.module.audit.entity.LlmCallLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LlmCallLogMapper extends BaseMapper<LlmCallLog> {
}

