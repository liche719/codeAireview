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

import java.util.List;

@Mapper
public interface RuleChunkMapper extends BaseMapper<RuleChunk> {

    @Delete("""
            DELETE FROM rule_chunk
            WHERE document_id = #{documentId}
            """)
    int deleteByDocumentId(@Param("documentId") Long documentId);

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
}
