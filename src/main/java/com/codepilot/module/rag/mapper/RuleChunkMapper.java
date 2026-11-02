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
            @Result(column = "chunk_index", property = "chunkIndex"),
            @Result(column = "content", property = "content"),
            @Result(column = "type", property = "type"),
            @Result(column = "distance", property = "distance")
    })
    @Select("""
            <script>
            SELECT id AS chunk_id,
                   document_id,
                   chunk_index,
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
    /**
     * 精确词检索（2026-11-02 改造）。
     *
     * <p><b>为什么需要</b>：团队规范里有 {@code @Transactional}、{@code ERR_502}、类名、方法名这类
     * **精确 token**，向量召回对它们天然弱——语义上"差不多"，但用户要的恰恰是"就是这一条"。
     *
     * <p><b>为什么要 trigram 索引</b>：原来这条查询是 {@code ILIKE '%..%'}，索引用不上，
     * 规范库一大就全表扫。V30 建了 {@code gin (content gin_trgm_ops)}，同样的 ILIKE 可以走索引。
     *
     * <p><b>为什么按 similarity 排序</b>：原来按 {@code length(content)} 升序，等于"最短的最相关"，
     * 纯属凑数；现在按三元组相似度排，命中更靠前的先返回。
     */
    @Select("""
            <script>
            SELECT id AS chunk_id,
                   document_id,
                   chunk_index,
                   content,
                   type,
                   CAST(0 AS double precision) AS distance
            FROM rule_chunk
            WHERE content ILIKE CONCAT('%', #{query}, '%')
            <if test="type != null and type != ''">
              AND type = #{type}
            </if>
            ORDER BY similarity(content, #{query}) DESC, length(content) ASC
            LIMIT #{topK}
            </script>
            """)
    List<RuleSearchRecord> searchByKeyword(
            @Param("query") String query,
            @Param("type") String type,
            @Param("topK") int topK
    );

    /**
     * 多词检索（2026-11-02）。
     *
     * <p><b>为什么需要</b>：实测发现原来那条 {@code content ILIKE '%整个查询%'} 只对**单个精确 token** 有效——
     * 查 {@code @Transactional} 或 {@code ERR_502} 能命中，但查「幂等 重复请求」「ThreadLocal 泄漏」
     * 一律 0 条（要求整串连续出现在正文里）。这不是"精确词通道"，是"精确整句通道"。
     *
     * <p>改成按词拆开后 OR 匹配（召回），再由调用方按**命中词数**重排（精度）。
     * 词由 Java 侧切分传入，避免在 SQL 里做中文分词。
     */
    @Select("""
            <script>
            SELECT id AS chunk_id,
                   document_id,
                   chunk_index,
                   content,
                   type,
                   CAST(0 AS double precision) AS distance
            FROM rule_chunk
            WHERE (
              <foreach collection="terms" item="term" separator=" OR ">
                content ILIKE CONCAT('%', #{term}, '%')
              </foreach>
            )
            <if test="type != null and type != ''">
              AND type = #{type}
            </if>
            ORDER BY similarity(content, #{query}) DESC, length(content) ASC
            LIMIT #{limit}
            </script>
            """)
    List<RuleSearchRecord> searchByTerms(
            @Param("terms") List<String> terms,
            @Param("query") String query,
            @Param("type") String type,
            @Param("limit") int limit
    );

    /**
     * 取同一文档里某几块的内容（2026-11-02，"小片检索、大片返回"的低成本版）。
     *
     * <p>没有 Rerank 模型时，命中精度靠小片，但小片上下文不全。
     * 这里在命中之后把**相邻块**一起拿回来补上下文，等价于"父子文档"的工程平替，
     * 不需要额外的模型，也不需要重新切库。
     */
    @Select("""
            <script>
            SELECT content
            FROM rule_chunk
            WHERE document_id = #{documentId}
              AND chunk_index BETWEEN #{fromIndex} AND #{toIndex}
            ORDER BY chunk_index
            </script>
            """)
    List<String> selectNeighborContents(
            @Param("documentId") Long documentId,
            @Param("fromIndex") int fromIndex,
            @Param("toIndex") int toIndex
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
