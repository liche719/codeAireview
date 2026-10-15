package com.codepilot.module.dlq.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codepilot.module.dlq.entity.DeadLetterMessage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DeadLetterMessageMapper extends BaseMapper<DeadLetterMessage> {

    /**
     * 写入一条死信记录；同一条消息反复死亡时只累加 attempt_count。
     *
     * <p>依赖 {@code uq_dead_letter_digest_status} 唯一索引，因此并发重复消费
     * 也不会产生重复行——这一点很重要，因为 DLQ 可能被多个实例同时消费。</p>
     *
     * <p>时间戳用 SQL 的 CURRENT_TIMESTAMP，而不是从实体取值：
     * 参数就是实体本身，它没有对应属性，写 {@code #{now}} 会在运行期报错。</p>
     */
    @Insert("""
            INSERT INTO dead_letter_message
                (source_queue, message_type, payload, payload_digest, error_type, error_message,
                 attempt_count, status, first_seen_at, last_seen_at)
            VALUES
                (#{sourceQueue}, #{messageType}, #{payload}, #{payloadDigest}, #{errorType}, #{errorMessage},
                 1, 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (payload_digest, status)
            DO UPDATE SET
                attempt_count = dead_letter_message.attempt_count + 1,
                error_type = EXCLUDED.error_type,
                error_message = EXCLUDED.error_message,
                source_queue = EXCLUDED.source_queue,
                last_seen_at = CURRENT_TIMESTAMP
            """)
    int upsertPending(DeadLetterMessage message);

    @Select("SELECT count(*) FROM dead_letter_message WHERE status = 'PENDING'")
    long countPending();
}
