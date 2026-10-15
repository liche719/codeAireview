package com.codepilot.module.command.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codepilot.module.command.entity.AgentRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRun> {

    @Select("SELECT * FROM agent_run WHERE command_task_id = #{commandTaskId} LIMIT 1")
    AgentRun findByCommandTaskId(@Param("commandTaskId") Long commandTaskId);

    @Select("""
            SELECT * FROM agent_run
            WHERE status = 'WAITING_WORKFLOW'
            ORDER BY updated_at
            LIMIT #{limit}
            """)
    List<AgentRun> findWaiting(@Param("limit") int limit);

    @Update("""
            UPDATE agent_run SET status = 'RESUMING', updated_at = CURRENT_TIMESTAMP
            WHERE id = #{runId} AND status = 'WAITING_WORKFLOW'
            """)
    int claimForResume(@Param("runId") Long runId);
}
