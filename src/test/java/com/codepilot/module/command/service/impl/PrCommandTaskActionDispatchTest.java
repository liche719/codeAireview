package com.codepilot.module.command.service.impl;

import com.codepilot.module.command.creator.PrCommandTaskCreator;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.entity.PrCommandTaskType;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import com.codepilot.module.command.runner.AgentChatTaskRunner;
import com.codepilot.module.command.runner.FixMergeTaskRunner;
import com.codepilot.module.command.runner.FixPublishTaskRunner;
import com.codepilot.module.command.runner.PrCommandTaskRunner;
import com.codepilot.module.command.state.PrCommandTaskStateManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrCommandTaskActionDispatchTest {

    @Test
    void shouldDispatchPublishAndMergeToSeparateRunners() {
        PrCommandTaskMapper mapper = mock(PrCommandTaskMapper.class);
        FixPublishTaskRunner publishRunner = mock(FixPublishTaskRunner.class);
        FixMergeTaskRunner mergeRunner = mock(FixMergeTaskRunner.class);
        PrCommandTaskServiceImpl service = new PrCommandTaskServiceImpl(
                mock(PrCommandTaskStateManager.class),
                mock(PrCommandTaskCreator.class),
                mock(PrCommandTaskRunner.class),
                mock(AgentChatTaskRunner.class),
                publishRunner,
                mergeRunner);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        PrCommandTask publishTask = task(1L, PrCommandTaskType.FIX_PUBLISH);
        PrCommandTask mergeTask = task(2L, PrCommandTaskType.FIX_MERGE);
        when(mapper.selectById(1L)).thenReturn(publishTask);
        when(mapper.selectById(2L)).thenReturn(mergeTask);

        service.processCommandTask(1L);
        service.processCommandTask(2L);

        verify(publishRunner).run(publishTask);
        verify(mergeRunner).run(mergeTask);
    }

    private PrCommandTask task(Long id, String type) {
        PrCommandTask task = new PrCommandTask();
        task.setId(id);
        task.setCommandType(type);
        task.setStatus("PENDING");
        return task;
    }
}
