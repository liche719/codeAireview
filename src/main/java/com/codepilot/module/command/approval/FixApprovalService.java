package com.codepilot.module.command.approval;

import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.creator.PrCommandTaskCreator;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.entity.PrCommandTaskType;
import com.codepilot.module.command.fix.FixPatchArtifactCodec;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import com.codepilot.module.command.policy.FixPullRequestWritePolicy;
import com.codepilot.module.command.queue.PrCommandTaskMessageDispatcher;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class FixApprovalService {

    private final GithubCommandProperties properties;

    private final FixApprovalCommandParser approvalCommandParser;

    private final FixPatchArtifactCodec patchArtifactCodec;

    private final PrCommandTaskMapper commandTaskMapper;

    private final PrCommandTaskCreator commandTaskCreator;

    private final GithubClient githubClient;

    private final FixPullRequestWritePolicy writePolicy;

    private PrCommandTaskMessageDispatcher messageDispatcher;

    @Autowired(required = false)
    public void configureMessageDispatcher(PrCommandTaskMessageDispatcher messageDispatcher) {
        this.messageDispatcher = messageDispatcher;
    }

    @Transactional(rollbackFor = Exception.class)
    public PrCommandTask approvePublish(Long approvalTaskId, Long previewTaskId) {
        if (!properties.isFixEnabled() || !properties.isFixPublishEnabled()) {
            throw new FixApprovalException("Fix publishing is disabled by server configuration.");
        }
        PrCommandTask approvalTask = requireAgentApprovalTask(approvalTaskId);
        if (!approvalCommandParser.isPublishApproval(approvalTask.getCommandText(), previewTaskId)) {
            throw new FixApprovalException(
                    "Explicit approval is required. Use: @x-pilotx 批准发布修复 " + previewTaskId);
        }
        PrCommandTask previewTask = requireTask(previewTaskId, "Fix preview task was not found.");
        requireTypeAndSuccess(previewTask, PrCommandTaskType.FIX_PREVIEW, "Fix preview is not publishable.");
        if (!Boolean.TRUE.equals(previewTask.getDryRun())) {
            throw new FixApprovalException("Only a dry-run preview can be approved for publishing.");
        }
        requireSameScope(approvalTask, previewTask);
        requireApprover(approvalTask, previewTask.getCommentUserLogin());
        requirePatchIntegrity(previewTask);
        requireFreshSameRepositoryPullRequest(previewTask);
        PrCommandTask task = commandTaskCreator.createPublishTask(approvalTask, previewTask);
        enqueue(task);
        return task;
    }

    @Transactional(rollbackFor = Exception.class)
    public PrCommandTask approveMerge(Long approvalTaskId, Long publishTaskId) {
        if (!properties.isFixMergeEnabled()) {
            throw new FixApprovalException("Fix PR merging is disabled by server configuration.");
        }
        PrCommandTask approvalTask = requireAgentApprovalTask(approvalTaskId);
        if (!approvalCommandParser.isMergeApproval(approvalTask.getCommandText(), publishTaskId)) {
            throw new FixApprovalException(
                    "Explicit approval is required. Use: @x-pilotx 批准合并修复 " + publishTaskId);
        }
        PrCommandTask publishTask = requireTask(publishTaskId, "Fix publish task was not found.");
        requireTypeAndSuccess(publishTask, PrCommandTaskType.FIX_PUBLISH, "Fix publish task is not mergeable.");
        requireSameScope(approvalTask, publishTask);
        requireApprover(approvalTask, publishTask.getApprovedBy());
        if (publishTask.getCreatedPrNumber() == null
                || !StringUtils.hasText(publishTask.getCreatedPrUrl())
                || !StringUtils.hasText(publishTask.getCommitSha())
                || !StringUtils.hasText(publishTask.getTargetBranch())) {
            throw new FixApprovalException("Fix publish task does not contain a completed pull request.");
        }
        requireFreshSameRepositoryPullRequest(publishTask);
        PrCommandTask task = commandTaskCreator.createMergeTask(approvalTask, publishTask);
        enqueue(task);
        return task;
    }

    private PrCommandTask requireAgentApprovalTask(Long taskId) {
        PrCommandTask task = requireTask(taskId, "Current Agent task was not found.");
        if (!PrCommandTaskType.CHAT.equalsIgnoreCase(task.getCommandType())) {
            throw new FixApprovalException("Approval must originate from an Agent comment task.");
        }
        if (!isAllowedAssociation(task.getCommentAuthorAssociation())) {
            throw new FixApprovalException("Comment author is not allowed to approve write operations.");
        }
        return task;
    }

    private void requireTypeAndSuccess(PrCommandTask task, String expectedType, String message) {
        if (!expectedType.equalsIgnoreCase(task.getCommandType())
                || !"SUCCESS".equalsIgnoreCase(task.getStatus())) {
            throw new FixApprovalException(message);
        }
    }

    private void requireSameScope(PrCommandTask approvalTask, PrCommandTask sourceTask) {
        if (!equalsIgnoreCase(approvalTask.getRepoOwner(), sourceTask.getRepoOwner())
                || !equalsIgnoreCase(approvalTask.getRepoName(), sourceTask.getRepoName())
                || !java.util.Objects.equals(approvalTask.getPrNumber(), sourceTask.getPrNumber())) {
            throw new FixApprovalException("Approval task and source task must belong to the same pull request.");
        }
    }

    private void requireApprover(PrCommandTask approvalTask, String originalRequester) {
        if (!StringUtils.hasText(approvalTask.getCommentUserLogin())) {
            throw new FixApprovalException("Approver identity is required.");
        }
        if (properties.isFixApprovalRequireSameUser()
                && !equalsIgnoreCase(approvalTask.getCommentUserLogin(), originalRequester)) {
            throw new FixApprovalException("Only the original requester can approve this fix action.");
        }
    }

    private void requirePatchIntegrity(PrCommandTask previewTask) {
        if (!patchArtifactCodec.matches(previewTask.getPatchPayload(), previewTask.getPatchSha256())
                || patchArtifactCodec.decodeAllowedPaths(previewTask.getAllowedPathsJson()).isEmpty()) {
            throw new FixApprovalException("Fix preview patch artifact is missing or failed integrity validation.");
        }
    }

    private void requireFreshSameRepositoryPullRequest(PrCommandTask task) {
        GithubPullRequestDetail detail = githubClient.getPullRequestDetail(
                task.getRepoOwner(), task.getRepoName(), task.getPrNumber());
        writePolicy.assertWritableSameRepo(task, detail);
        if (!StringUtils.hasText(task.getHeadSha())
                || !task.getHeadSha().equalsIgnoreCase(detail.getHeadSha())) {
            throw new FixApprovalException("The source pull request changed after the fix preview. Generate a new preview.");
        }
    }

    private PrCommandTask requireTask(Long taskId, String message) {
        PrCommandTask task = taskId == null ? null : commandTaskMapper.selectById(taskId);
        if (task == null) {
            throw new FixApprovalException(message);
        }
        return task;
    }

    private boolean isAllowedAssociation(String association) {
        if (!StringUtils.hasText(association) || properties.getAllowedCommentAuthorAssociations() == null) {
            return false;
        }
        String normalized = association.trim().toUpperCase(Locale.ROOT);
        return properties.getAllowedCommentAuthorAssociations().stream()
                .filter(StringUtils::hasText)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .anyMatch(normalized::equals);
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return StringUtils.hasText(left) && StringUtils.hasText(right) && left.equalsIgnoreCase(right);
    }

    private void enqueue(PrCommandTask task) {
        if (messageDispatcher != null && task != null
                && !"SUCCESS".equalsIgnoreCase(task.getStatus())
                && !"FAILED".equalsIgnoreCase(task.getStatus())) {
            messageDispatcher.enqueue(task.getId());
        }
    }
}
