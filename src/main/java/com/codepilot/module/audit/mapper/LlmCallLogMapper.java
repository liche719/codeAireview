package com.codepilot.module.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codepilot.module.audit.entity.LlmCallLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface LlmCallLogMapper extends BaseMapper<LlmCallLog> {

    @Delete("""
            DELETE FROM llm_call_log
            WHERE created_at < #{createdBefore}
            """)
    int deleteCreatedBefore(@Param("createdBefore") LocalDateTime createdBefore);
}
