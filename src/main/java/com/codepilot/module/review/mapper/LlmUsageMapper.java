package com.codepilot.module.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codepilot.module.review.cost.LlmUsage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LlmUsageMapper extends BaseMapper<LlmUsage> {
}
