package com.codepilot.module.review.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class ReviewFileExecutorConfig {

    @Bean
    public Executor reviewFileExecutor(ReviewProperties reviewProperties) {
        return newReviewFileExecutor(reviewProperties);
    }

    public static Executor newReviewFileExecutor(ReviewProperties reviewProperties) {
        int poolSize = Math.max(1, reviewProperties == null ? 1 : reviewProperties.getMaxParallelFiles());
        int queueCapacity = Math.max(1, reviewProperties == null ? poolSize : reviewProperties.getMaxFilesPerTask());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("codepilot-review-file-");
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setDaemon(true);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
