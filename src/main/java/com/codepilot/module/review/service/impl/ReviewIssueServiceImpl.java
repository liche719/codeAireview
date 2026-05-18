package com.codepilot.module.review.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.codepilot.module.review.entity.ReviewIssue;
import com.codepilot.module.review.mapper.ReviewIssueMapper;
import com.codepilot.module.review.service.ReviewIssueService;
import org.springframework.stereotype.Service;

@Service
public class ReviewIssueServiceImpl extends ServiceImpl<ReviewIssueMapper, ReviewIssue> implements ReviewIssueService {
}

