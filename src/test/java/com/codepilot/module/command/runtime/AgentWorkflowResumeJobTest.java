package com.codepilot.module.command.runtime;

import com.codepilot.module.command.config.AgentRuntimeProperties;
import com.codepilot.module.command.entity.AgentRun;
import com.codepilot.module.command.mapper.AgentRunMapper;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import com.codepilot.module.command.queue.PrCommandTaskMessageDispatcher;
import com.codepilot.module.command.state.PrCommandTaskStateManager;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentWorkflowResumeJobTest {

    @Test
    void shouldResumeAgentAfterReviewWorkflowCompletes() {
        AgentRunMapper runMapper = mock(AgentRunMapper.class);
        AgentRunService runService = mock(AgentRunService.class);
        ReviewTaskMapper reviewMapper = mock(ReviewTaskMapper.class);
        PrCommandTaskMapper commandMapper = mock(PrCommandTaskMapper.class);
        PrCommandTaskStateManager stateManager = mock(PrCommandTaskStateManager.class);
        PrCommandTaskMessageDispatcher dispatcher = mock(PrCommandTaskMessageDispatcher.class);
        AgentRun run = new AgentRun();
        run.setId(5L);
        run.setCommandTaskId(10L);
        run.setStatus("WAITING_WORKFLOW");
        run.setWaitingWorkflowType("REVIEW");
        run.setWaitingWorkflowTaskId(20L);
        run.setDeadlineAt(LocalDateTime.now().plusMinutes(5));
        ReviewTask review = new ReviewTask();
        review.setId(20L);
        review.setStatus("SUCCESS");
        review.setTotalFiles(3);
        review.setTotalIssues(1);
        review.setRiskLevel("MEDIUM");
        when(runMapper.findWaiting(50)).thenReturn(List.of(run));
        when(reviewMapper.selectById(20L)).thenReturn(review);
        when(runMapper.claimForResume(5L)).thenReturn(1);
        when(stateManager.resumeWaiting(10L)).thenReturn(true);
        AgentWorkflowResumeJob job = new AgentWorkflowResumeJob(
                runMapper, runService, reviewMapper, commandMapper,
                stateManager, dispatcher, new AgentRuntimeProperties());

        job.resumeCompletedWorkflows();

        verify(runService).prepareResume(org.mockito.ArgumentMatchers.eq(run), contains("workflowStatus: SUCCESS"));
        verify(stateManager).resumeWaiting(10L);
        verify(dispatcher).enqueue(10L);
    }
}
