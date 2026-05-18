package com.codepilot.module.review.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.codepilot.module.review.dto.ReviewCreateResponse;
import com.codepilot.module.review.entity.ReviewTask;

public interface ReviewTaskService extends IService<ReviewTask> {

    ReviewCreateResponse createTask(String prUrl);

    ReviewCreateResponse createTask(String prUrl, String title);

    void processTask(Long taskId);
}
