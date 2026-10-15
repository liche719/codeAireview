package com.codepilot.module.command.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codepilot.module.command.entity.PrCommandTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PrCommandTaskMapper extends BaseMapper<PrCommandTask> {

    @Update("""
            UPDATE pr_command_task
            SET status = 'RUNNING', execution_token = #{executionToken}, lease_until = #{leaseUntil},
                attempt_count = attempt_count + 1, started_at = COALESCE(started_at, #{now}),
                error_message = NULL, updated_at = #{now}
            WHERE id = #{taskId}
              AND (status = 'PENDING' OR (status = 'RUNNING' AND lease_until IS NOT NULL AND lease_until < #{now}))
            """)
    int claimForExecution(
            @Param("taskId") Long taskId,
            @Param("executionToken") String executionToken,
            @Param("now") LocalDateTime now,
            @Param("leaseUntil") LocalDateTime leaseUntil
    );

    @Select("""
            SELECT * FROM pr_command_task
            WHERE status = 'RUNNING' AND lease_until IS NOT NULL AND lease_until < #{now}
            ORDER BY lease_until LIMIT #{limit}
            """)
    List<PrCommandTask> findExpiredExecutions(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            UPDATE pr_command_task
            SET status = 'PENDING', execution_token = NULL, lease_until = NULL,
                error_message = 'execution lease expired', updated_at = #{now}
            WHERE id = #{taskId} AND status = 'RUNNING'
              AND execution_token = #{executionToken} AND lease_until < #{now}
            """)
    int recoverExpiredExecution(
            @Param("taskId") Long taskId,
            @Param("executionToken") String executionToken,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE pr_command_task
            SET status = 'PENDING', execution_token = NULL, lease_until = NULL, updated_at = #{now}
            WHERE id = #{taskId} AND status = 'WAITING_WORKFLOW'
            """)
    int resumeWaiting(@Param("taskId") Long taskId, @Param("now") LocalDateTime now);
}
