package com.codepilot.module.command.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.codepilot.module.command.creator.PrCommandTaskCreator;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import com.codepilot.module.command.runner.PrCommandTaskRunner;
import com.codepilot.module.command.runner.AgentChatTaskRunner;
import com.codepilot.module.command.runner.FixPublishTaskRunner;
import com.codepilot.module.command.runner.FixMergeTaskRunner;
import com.codepilot.module.command.entity.PrCommandTaskType;
import com.codepilot.module.command.queue.PrCommandTaskMessageDispatcher;
import com.codepilot.module.command.service.PrCommandTaskService;
import com.codepilot.module.command.state.PrCommandTaskStateManager;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class PrCommandTaskServiceImpl extends ServiceImpl<PrCommandTaskMapper, PrCommandTask>
        implements PrCommandTaskService {

    private final PrCommandTaskStateManager commandTaskStateManager;

    private final PrCommandTaskCreator commandTaskCreator;

    private final PrCommandTaskRunner commandTaskRunner;

    private final AgentChatTaskRunner agentChatTaskRunner;

    private final FixPublishTaskRunner fixPublishTaskRunner;

    private final FixMergeTaskRunner fixMergeTaskRunner;

    private final PrCommandTaskMessageDispatcher messageDispatcher;

    private boolean reliableClaimEnabled = true;

    @Autowired
    public PrCommandTaskServiceImpl(
            PrCommandTaskStateManager commandTaskStateManager,
            PrCommandTaskCreator commandTaskCreator,
            PrCommandTaskRunner commandTaskRunner,
            AgentChatTaskRunner agentChatTaskRunner,
            FixPublishTaskRunner fixPublishTaskRunner,
            FixMergeTaskRunner fixMergeTaskRunner,
            PrCommandTaskMessageDispatcher messageDispatcher
    ) {
        this.commandTaskStateManager = commandTaskStateManager;
        this.commandTaskCreator = commandTaskCreator;
        this.commandTaskRunner = commandTaskRunner;
        this.agentChatTaskRunner = agentChatTaskRunner;
        this.fixPublishTaskRunner = fixPublishTaskRunner;
        this.fixMergeTaskRunner = fixMergeTaskRunner;
        this.messageDispatcher = messageDispatcher;
    }

    public PrCommandTaskServiceImpl(
            PrCommandTaskStateManager commandTaskStateManager,
            PrCommandTaskCreator commandTaskCreator,
            PrCommandTaskRunner commandTaskRunner
    ) {
        this(commandTaskStateManager, commandTaskCreator, commandTaskRunner, null, null, null, null);
        this.reliableClaimEnabled = false;
    }

    public PrCommandTaskServiceImpl(
            PrCommandTaskStateManager commandTaskStateManager,
            PrCommandTaskCreator commandTaskCreator,
            PrCommandTaskRunner commandTaskRunner,
            AgentChatTaskRunner agentChatTaskRunner,
            FixPublishTaskRunner fixPublishTaskRunner,
            FixMergeTaskRunner fixMergeTaskRunner
    ) {
        this(commandTaskStateManager, commandTaskCreator, commandTaskRunner,
                agentChatTaskRunner, fixPublishTaskRunner, fixMergeTaskRunner, null);
        this.reliableClaimEnabled = false;
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrCommandTask createFixTask(GitHubPullRequestWebhookPayload payload) {
        PrCommandTask task = commandTaskCreator.createFixTask(payload);
        enqueue(task);
        return task;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PrCommandTask createChatTask(GitHubPullRequestWebhookPayload payload) {
        PrCommandTask task = commandTaskCreator.createChatTask(payload);
        enqueue(task);
        return task;
    }

    @Override
    public void processCommandTask(Long commandTaskId) {
        PrCommandTask task = reliableClaimEnabled
                ? commandTaskStateManager.claim(commandTaskId)
                : getById(commandTaskId);
        if (task == null) {
            log.info("Skip PR command task message because execution was not claimable, commandTaskId={}", commandTaskId);
            return;
        }
        if (!reliableClaimEnabled && commandTaskStateManager.isTerminalStatus(task.getStatus())) {
            return;
        }
        if (PrCommandTaskType.CHAT.equalsIgnoreCase(task.getCommandType())) {
            if (agentChatTaskRunner == null) {
                throw new IllegalStateException("Agent chat task runner is unavailable");
            }
            agentChatTaskRunner.run(task);
            return;
        }
        if (PrCommandTaskType.FIX_PREVIEW.equalsIgnoreCase(task.getCommandType())) {
            commandTaskRunner.run(task);
            return;
        }
        if (PrCommandTaskType.FIX_PUBLISH.equalsIgnoreCase(task.getCommandType())) {
            if (fixPublishTaskRunner == null) {
                throw new IllegalStateException("Fix publish task runner is unavailable");
            }
            fixPublishTaskRunner.run(task);
            return;
        }
        if (PrCommandTaskType.FIX_MERGE.equalsIgnoreCase(task.getCommandType())) {
            if (fixMergeTaskRunner == null) {
                throw new IllegalStateException("Fix merge task runner is unavailable");
            }
            fixMergeTaskRunner.run(task);
            return;
        }
        commandTaskStateManager.markFailed(task, "Unsupported command type: " + task.getCommandType());
    }

    @Override
    public void processFixTask(Long commandTaskId) {
        PrCommandTask task = reliableClaimEnabled
                ? commandTaskStateManager.claim(commandTaskId)
                : getById(commandTaskId);
        if (task == null) {
            log.info("Skip PR fix task message because execution was not claimable, commandTaskId={}", commandTaskId);
            return;
        }
        if (!reliableClaimEnabled && commandTaskStateManager.isTerminalStatus(task.getStatus())) {
            return;
        }

        commandTaskRunner.run(task);
    }

    private void enqueue(PrCommandTask task) {
        if (task != null && messageDispatcher != null
                && !commandTaskStateManager.isTerminalStatus(task.getStatus())) {
            messageDispatcher.enqueue(task.getId());
        }
    }

}
