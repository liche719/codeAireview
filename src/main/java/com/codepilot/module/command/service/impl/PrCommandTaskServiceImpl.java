package com.codepilot.module.command.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.codepilot.module.command.creator.PrCommandTaskCreator;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import com.codepilot.module.command.runner.PrCommandTaskRunner;
import com.codepilot.module.command.service.PrCommandTaskService;
import com.codepilot.module.command.state.PrCommandTaskStateManager;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrCommandTaskServiceImpl extends ServiceImpl<PrCommandTaskMapper, PrCommandTask>
        implements PrCommandTaskService {

    private final PrCommandTaskStateManager commandTaskStateManager;

    private final PrCommandTaskCreator commandTaskCreator;

    private final PrCommandTaskRunner commandTaskRunner;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrCommandTask createFixTask(GitHubPullRequestWebhookPayload payload) {
        return commandTaskCreator.createFixTask(payload);
    }

    @Override
    public void processFixTask(Long commandTaskId) {
        PrCommandTask task = getById(commandTaskId);
        if (task == null) {
            log.warn("PR command task not found, commandTaskId={}", commandTaskId);
            return;
        }

        if (commandTaskStateManager.isTerminalStatus(task.getStatus())) {
            log.info("Skip terminal PR command task message, commandTaskId={}, status={}",
                    commandTaskId, task.getStatus());
            return;
        }

        commandTaskRunner.run(task);
    }

}
