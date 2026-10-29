package com.codepilot.module.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codepilot.module.rag.dto.RuleSearchRecord;
import com.codepilot.module.rag.entity.RuleChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface RuleChunkMapper extends BaseMapper<RuleChunk> {

    @Delete("""
            DELETE FROM rule_chunk
            WHERE document_id = #{documentId}
            """)
    int deleteByDocumentId(@Param("documentId") Long documentId);

    @Update("""
            DELETE FROM rule_chunk
            """)
    int deleteAll();

    @Insert("""
            INSERT INTO rule_chunk(document_id, chunk_index, content, embedding, type)
            VALUES (#{documentId}, #{chunkIndex}, #{content}, CAST(#{embedding} AS vector), #{type})
            """)
    int insertVector(RuleChunk ruleChunk);

    @Results(id = "RuleSearchRecordMap", value = {
            @Result(column = "chunk_id", property = "chunkId"),
            @Result(column = "document_id", property = "documentId"),
            @Result(column = "content", property = "content"),
            @Result(column = "type", property = "type"),
            @Result(column = "distance", property = "distance")
    })
    @Select("""
            <script>
            SELECT id AS chunk_id,
                   document_id,
                   content,
                   type,
                   embedding &lt;=&gt; CAST(#{embedding} AS vector) AS distance
            FROM rule_chunk
            WHERE embedding IS NOT NULL
            <if test="type != null and type != ''">
              AND type = #{type}
            </if>
            ORDER BY embedding &lt;=&gt; CAST(#{embedding} AS vector)
            LIMIT #{topK}
            </script>
            """)
    List<RuleSearchRecord> search(
            @Param("embedding") String embedding,
            @Param("type") String type,
            @Param("topK") int topK
    );

    /**
     * 关键词兜底检索（2026-10-29）。
     *
     * <p><b>为什么需要</b>：真实部署里 agent 调 `searchTeamRules` 返回
     * `BusinessException: embedding is disabled`——因为没配 embedding key，
     * 于是"查团队规范"这个能力**在线上直接不可用**，而模型只能回一句"查不到"。
     * 企业部署里 embedding 服务常常是最后才接的，这期间不该整条 RAG 作废。
     *
     * <p>做法：对 content 做 ILIKE 匹配，并按命中次数粗略排序；命中 0 条就返回空，
     * 让调用方明确知道"是没匹配到"，而不是"服务挂了"。
     */
    @Select("""
            <script>
            SELECT id AS chunk_id,
                   document_id,
                   content,
                   type,
                   CAST(0 AS double precision) AS distance
            FROM rule_chunk
            WHERE content ILIKE CONCAT('%', #{query}, '%')
            <if test="type != null and type != ''">
              AND type = #{type}
            </if>
            ORDER BY length(content) ASC
            LIMIT #{topK}
            </script>
            """)
    List<RuleSearchRecord> searchByKeyword(
            @Param("query") String query,
            @Param("type") String type,
            @Param("topK") int topK
    );

    @Select("""
            SELECT format_type(a.atttypid, a.atttypmod)
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = current_schema()
              AND c.relname = 'rule_chunk'
              AND a.attname = 'embedding'
              AND a.attnum > 0
              AND NOT a.attisdropped
            """)
    String selectEmbeddingColumnType();

    @Select("""
            SELECT DISTINCT vector_dims(embedding)
            FROM rule_chunk
            WHERE embedding IS NOT NULL
            ORDER BY 1
            """)
    List<Integer> selectEmbeddingDimensions();

    @Update("""
            ALTER TABLE rule_chunk
            ALTER COLUMN embedding TYPE vector
            USING embedding::vector
            """)
    int alterEmbeddingColumnToFlexibleVector();
}
