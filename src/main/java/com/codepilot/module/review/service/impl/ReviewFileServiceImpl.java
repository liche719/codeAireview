package com.codepilot.module.review.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.mapper.ReviewFileMapper;
import com.codepilot.module.review.service.ReviewFileService;
import org.springframework.stereotype.Service;

@Service
public class ReviewFileServiceImpl extends ServiceImpl<ReviewFileMapper, ReviewFile> implements ReviewFileService {
}

