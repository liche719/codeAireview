package com.codepilot.module.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codepilot.module.review.entity.ReviewTaskOutbox;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ReviewTaskOutboxMapper extends BaseMapper<ReviewTaskOutbox> {

    @Insert("""
            INSERT INTO review_task_outbox(task_id, status, attempt_count, next_attempt_at, created_at, updated_at)
            VALUES (#{taskId}, 'PENDING', 0, #{now}, #{now}, #{now})
            ON CONFLICT (task_id) DO UPDATE
            SET status = 'PENDING',
                next_attempt_at = EXCLUDED.next_attempt_at,
                locked_at = NULL,
                last_error = NULL,
                updated_at = EXCLUDED.updated_at
            """)
    int enqueue(@Param("taskId") Long taskId, @Param("now") LocalDateTime now);

    @Select("""
            WITH due AS (
                SELECT id
                FROM review_task_outbox
                WHERE (status = 'PENDING' AND next_attempt_at <= #{now})
                   OR (status = 'PUBLISHING' AND locked_at < #{staleBefore})
                ORDER BY id
                FOR UPDATE SKIP LOCKED
                LIMIT #{limit}
            )
            UPDATE review_task_outbox outbox
            SET status = 'PUBLISHING',
                locked_at = #{now},
                updated_at = #{now}
            FROM due
            WHERE outbox.id = due.id
            RETURNING outbox.*
            """)
    List<ReviewTaskOutbox> claimDue(
            @Param("now") LocalDateTime now,
            @Param("staleBefore") LocalDateTime staleBefore,
            @Param("limit") int limit
    );

    @Update("""
            UPDATE review_task_outbox
            SET status = 'PUBLISHED',
                locked_at = NULL,
                last_error = NULL,
                updated_at = #{now}
            WHERE id = #{id} AND status = 'PUBLISHING'
            """)
    int markPublished(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE review_task_outbox
            SET status = 'PENDING',
                attempt_count = attempt_count + 1,
                next_attempt_at = #{nextAttemptAt},
                locked_at = NULL,
                last_error = #{lastError},
                updated_at = #{now}
            WHERE id = #{id} AND status = 'PUBLISHING'
            """)
    int markRetry(
            @Param("id") Long id,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("lastError") String lastError,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE review_task_outbox
            SET status = 'FAILED',
                attempt_count = attempt_count + 1,
                locked_at = NULL,
                last_error = #{lastError},
                updated_at = #{now}
            WHERE id = #{id} AND status = 'PUBLISHING'
            """)
    int markFailed(
            @Param("id") Long id,
            @Param("lastError") String lastError,
            @Param("now") LocalDateTime now
    );
}
