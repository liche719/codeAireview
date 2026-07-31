package com.codepilot.module.review.queue;

import com.codepilot.module.review.mapper.ReviewTaskOutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class ReviewTaskMessageDispatcher {

    private final ReviewTaskOutboxMapper reviewTaskOutboxMapper;

    public void enqueue(Long taskId) {
        reviewTaskOutboxMapper.enqueue(taskId, LocalDateTime.now());
    }
}
