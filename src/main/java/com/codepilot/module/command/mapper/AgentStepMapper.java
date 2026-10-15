package com.codepilot.module.command.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codepilot.module.command.entity.AgentStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentStepMapper extends BaseMapper<AgentStep> {

    @Select("SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM agent_step WHERE agent_run_id = #{runId}")
    int nextSequence(@Param("runId") Long runId);
}
