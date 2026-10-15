package com.codepilot.module.review.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ReviewFindingMemoryMapper extends BaseMapper<ReviewFindingMemory> {

    @Select("""
            SELECT *
            FROM review_finding_memory
            WHERE repo_owner = #{repoOwner}
              AND repo_name = #{repoName}
              AND pr_number = #{prNumber}
            """)
    List<ReviewFindingMemory> findByPullRequest(
            @Param("repoOwner") String repoOwner,
            @Param("repoName") String repoName,
            @Param("prNumber") Integer prNumber
    );

    /**
     * 已存在则更新为 ACTIVE 并累加 reopened_count，否则插入。
     *
     * <p>唯一索引 {@code uq_finding_memory_key} 让并发重复消费安全。
     * 时间戳用 SQL 的 CURRENT_TIMESTAMP：参数是实体，它没有 now 属性。</p>
     */
    @Update("""
            INSERT INTO review_finding_memory
                (repo_owner, repo_name, pr_number, finding_fingerprint, code_fingerprint,
                 issue_type, file_path, severity, title, state, reopened_count,
                 first_task_id, last_task_id, first_seen_head_sha, last_seen_head_sha,
                 resolved_at_head_sha, suppressed_reason, first_seen_at, last_seen_at)
            VALUES
                (#{repoOwner}, #{repoName}, #{prNumber}, #{findingFingerprint}, #{codeFingerprint},
                 #{issueType}, #{filePath}, #{severity}, #{title}, 'ACTIVE', 0,
                 #{firstTaskId}, #{lastTaskId}, #{firstSeenHeadSha}, #{lastSeenHeadSha},
                 NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (repo_owner, repo_name, pr_number, finding_fingerprint)
            DO UPDATE SET
                state = 'ACTIVE',
                reopened_count = CASE
                    WHEN review_finding_memory.state = 'ACTIVE' THEN review_finding_memory.reopened_count
                    ELSE review_finding_memory.reopened_count + 1
                END,
                code_fingerprint = EXCLUDED.code_fingerprint,
                issue_type = EXCLUDED.issue_type,
                file_path = EXCLUDED.file_path,
                severity = EXCLUDED.severity,
                title = EXCLUDED.title,
                last_task_id = EXCLUDED.last_task_id,
                last_seen_head_sha = EXCLUDED.last_seen_head_sha,
                resolved_at_head_sha = NULL,
                suppressed_reason = NULL,
                last_seen_at = CURRENT_TIMESTAMP
            """)
    int upsertActive(ReviewFindingMemory memory);

    /**
     * 把未再现的 finding 标为 RESOLVED。
     *
     * <p><b>调用方必须先确认这是一次"完整审查"</b>——如果本次有文件因预算没被审查，
     * 某个问题"没再出现"可能只是因为它所在的文件没被看到，标成"已修复"是错误结论。
     * 这个判断刻意放在服务层，而不是写进 SQL。</p>
     */
    @Update("""
            UPDATE review_finding_memory
            SET state = 'RESOLVED',
                resolved_at_head_sha = #{headSha},
                last_seen_at = CURRENT_TIMESTAMP
            WHERE repo_owner = #{repoOwner}
              AND repo_name = #{repoName}
              AND pr_number = #{prNumber}
              AND finding_fingerprint = #{findingFingerprint}
              AND state = 'ACTIVE'
            """)
    int markResolved(
            @Param("repoOwner") String repoOwner,
            @Param("repoName") String repoName,
            @Param("prNumber") Integer prNumber,
            @Param("findingFingerprint") String findingFingerprint,
            @Param("headSha") String headSha
    );
}
