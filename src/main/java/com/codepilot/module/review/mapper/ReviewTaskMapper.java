package com.codepilot.module.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codepilot.module.review.entity.ReviewTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ReviewTaskMapper extends BaseMapper<ReviewTask> {

    @Update("""
            UPDATE review_task
            SET status = 'RUNNING',
                execution_token = #{executionToken},
                lease_until = #{leaseUntil},
                attempt_count = attempt_count + 1,
                started_at = COALESCE(started_at, #{now}),
                error_message = NULL,
                updated_at = #{now}
            WHERE id = #{taskId}
              AND (
                    status = 'PENDING'
                    OR (status = 'RUNNING' AND lease_until IS NOT NULL AND lease_until < #{now})
              )
            """)
    int claimForExecution(
            @Param("taskId") Long taskId,
            @Param("executionToken") String executionToken,
            @Param("now") LocalDateTime now,
            @Param("leaseUntil") LocalDateTime leaseUntil
    );

    @Update("""
            UPDATE review_task
            SET status = 'PENDING',
                execution_token = NULL,
                lease_until = NULL,
                error_message = #{errorMessage},
                updated_at = #{now}
            WHERE id = #{taskId}
              AND status = 'RUNNING'
              AND execution_token = #{executionToken}
            """)
    int releaseForRetry(
            @Param("taskId") Long taskId,
            @Param("executionToken") String executionToken,
            @Param("errorMessage") String errorMessage,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE review_task
            SET status = 'PENDING',
                execution_token = NULL,
                lease_until = NULL,
                error_message = 'execution lease expired',
                updated_at = #{now}
            WHERE id = #{taskId}
              AND status = 'RUNNING'
              AND execution_token = #{executionToken}
              AND lease_until < #{now}
            """)
    int recoverExpiredExecution(
            @Param("taskId") Long taskId,
            @Param("executionToken") String executionToken,
            @Param("now") LocalDateTime now
    );

    @Select("""
            SELECT *
            FROM review_task
            WHERE status = 'RUNNING'
              AND lease_until IS NOT NULL
              AND lease_until < #{now}
            ORDER BY lease_until
            LIMIT #{limit}
            """)
    List<ReviewTask> findExpiredExecutions(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit
    );

    @Update("""
            UPDATE review_task
            SET status = 'SUCCESS',
                total_files = #{totalFiles},
                total_issues = #{totalIssues},
                risk_level = #{riskLevel},
                error_message = NULL,
                execution_token = NULL,
                lease_until = NULL,
                publish_status = 'PENDING',
                publish_error = NULL,
                publish_next_attempt_at = #{now},
                finished_at = #{now},
                updated_at = #{now}
            WHERE id = #{taskId}
              AND status = 'RUNNING'
              AND execution_token = #{executionToken}
            """)
    int completeExecution(
            @Param("taskId") Long taskId,
            @Param("executionToken") String executionToken,
            @Param("totalFiles") int totalFiles,
            @Param("totalIssues") int totalIssues,
            @Param("riskLevel") String riskLevel,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE review_task
            SET status = 'FAILED',
                error_message = #{errorMessage},
                execution_token = NULL,
                lease_until = NULL,
                finished_at = #{now},
                updated_at = #{now}
            WHERE id = #{taskId}
              AND status = 'RUNNING'
              AND execution_token = #{executionToken}
            """)
    int failExecution(
            @Param("taskId") Long taskId,
            @Param("executionToken") String executionToken,
            @Param("errorMessage") String errorMessage,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE review_task
            SET publish_status = 'PUBLISHING',
                publish_attempt_count = publish_attempt_count + 1,
                publish_error = NULL,
                updated_at = #{now}
            WHERE id = #{taskId}
              AND status = 'SUCCESS'
              AND (
                    (publish_status IN ('PENDING', 'FAILED')
                     AND (publish_next_attempt_at IS NULL OR publish_next_attempt_at <= #{now}))
                    OR (publish_status = 'PUBLISHING' AND updated_at < #{staleBefore})
              )
            """)
    int claimPublication(
            @Param("taskId") Long taskId,
            @Param("now") LocalDateTime now,
            @Param("staleBefore") LocalDateTime staleBefore
    );

    @Select("""
            SELECT id
            FROM review_task
            WHERE status = 'SUCCESS'
              AND (
                    (publish_status IN ('PENDING', 'FAILED')
                     AND (publish_next_attempt_at IS NULL OR publish_next_attempt_at <= #{now}))
                    OR (publish_status = 'PUBLISHING' AND updated_at < #{staleBefore})
              )
            ORDER BY updated_at
            LIMIT #{limit}
            """)
    List<Long> findPublicationCandidates(
            @Param("now") LocalDateTime now,
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("limit") int limit
    );

    @Update("""
            UPDATE review_task
            SET publish_status = #{publishStatus},
                publish_error = #{publishError},
                publish_next_attempt_at = #{nextAttemptAt},
                updated_at = #{now}
            WHERE id = #{taskId}
              AND publish_status = 'PUBLISHING'
            """)
    int finishPublication(
            @Param("taskId") Long taskId,
            @Param("publishStatus") String publishStatus,
            @Param("publishError") String publishError,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("now") LocalDateTime now
    );
}

