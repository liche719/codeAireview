package com.codepilot.module.command.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codepilot.module.command.entity.AgentWebResource;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface AgentWebResourceMapper extends BaseMapper<AgentWebResource> {

    @Insert("""
            INSERT INTO agent_web_resource(agent_run_id, url_hash, url, created_at)
            VALUES (#{runId}, #{urlHash}, #{url}, #{now})
            ON CONFLICT (agent_run_id, url_hash) DO NOTHING
            """)
    int register(@Param("runId") Long runId, @Param("urlHash") String urlHash,
                 @Param("url") String url, @Param("now") LocalDateTime now);

    @Select("""
            SELECT COUNT(*) FROM agent_web_resource
            WHERE agent_run_id = #{runId} AND url_hash = #{urlHash} AND url = #{url}
            """)
    int existsForRun(@Param("runId") Long runId, @Param("urlHash") String urlHash, @Param("url") String url);
}
