package com.codepilot.module.command.approval;

import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.creator.PrCommandTaskCreator;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.entity.PrCommandTaskType;
import com.codepilot.module.command.fix.FixPatchArtifact;
import com.codepilot.module.command.fix.FixPatchArtifactCodec;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import com.codepilot.module.command.policy.FixPullRequestWritePolicy;
import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FixApprovalServiceTest {

    @Test
    void shouldApproveExactFreshPreviewFromOriginalRequester() {
        TestContext context = new TestContext();
        PrCommandTask approval = context.approvalTask("批准发布修复 20", "reviewer");
        PrCommandTask preview = context.previewTask();
        PrCommandTask publish = new PrCommandTask();
        publish.setId(30L);
        when(context.mapper.selectById(10L)).thenReturn(approval);
        when(context.mapper.selectById(20L)).thenReturn(preview);
        when(context.githubClient.getPullRequestDetail("liche719", "codeAireview", 12))
                .thenReturn(context.sourcePullRequest());
        when(context.creator.createPublishTask(approval, preview)).thenReturn(publish);

        assertThat(context.service.approvePublish(10L, 20L)).isSameAs(publish);

        verify(context.creator).createPublishTask(approval, preview);
    }

    @Test
    void shouldRejectModelInferredApprovalWhenOriginalCommentIsNotExact() {
        TestContext context = new TestContext();
        when(context.mapper.selectById(10L))
                .thenReturn(context.approvalTask("这个补丁看起来还行，你处理一下", "reviewer"));

        assertThatThrownBy(() -> context.service.approvePublish(10L, 20L))
                .isInstanceOf(FixApprovalException.class)
                .hasMessageContaining("Explicit approval");
    }

    @Test
    void shouldRejectDifferentApproverByDefault() {
        TestContext context = new TestContext();
        when(context.mapper.selectById(10L))
                .thenReturn(context.approvalTask("批准发布修复 20", "other-reviewer"));
        when(context.mapper.selectById(20L)).thenReturn(context.previewTask());

        assertThatThrownBy(() -> context.service.approvePublish(10L, 20L))
                .isInstanceOf(FixApprovalException.class)
                .hasMessageContaining("original requester");
    }

    @Test
    void shouldRejectStalePreviewAfterSourcePullRequestChanges() {
        TestContext context = new TestContext();
        when(context.mapper.selectById(10L))
                .thenReturn(context.approvalTask("批准发布修复 20", "reviewer"));
        when(context.mapper.selectById(20L)).thenReturn(context.previewTask());
        GithubPullRequestDetail changed = context.sourcePullRequest();
        changed.setHeadSha("changed-sha");
        when(context.githubClient.getPullRequestDetail("liche719", "codeAireview", 12)).thenReturn(changed);

        assertThatThrownBy(() -> context.service.approvePublish(10L, 20L))
                .isInstanceOf(FixApprovalException.class)
                .hasMessageContaining("changed");
    }

    @Test
    void shouldCreateSeparateMergeTaskOnlyForSuccessfulPublish() {
        TestContext context = new TestContext();
        PrCommandTask approval = context.approvalTask("批准合并修复 30", "reviewer");
        PrCommandTask publish = context.publishTask();
        PrCommandTask merge = new PrCommandTask();
        merge.setId(40L);
        when(context.mapper.selectById(10L)).thenReturn(approval);
        when(context.mapper.selectById(30L)).thenReturn(publish);
        when(context.githubClient.getPullRequestDetail("liche719", "codeAireview", 12))
                .thenReturn(context.sourcePullRequest());
        when(context.creator.createMergeTask(approval, publish)).thenReturn(merge);

        assertThat(context.service.approveMerge(10L, 30L)).isSameAs(merge);
    }

    private static class TestContext {

        private final GithubCommandProperties properties = properties();
        private final PrCommandTaskMapper mapper = mock(PrCommandTaskMapper.class);
        private final PrCommandTaskCreator creator = mock(PrCommandTaskCreator.class);
        private final GithubClient githubClient = mock(GithubClient.class);
        private final FixPatchArtifactCodec codec = new FixPatchArtifactCodec(new ObjectMapper(), properties);
        private final FixApprovalService service = new FixApprovalService(
                properties,
                new FixApprovalCommandParser(),
                codec,
                mapper,
                creator,
                githubClient,
                new FixPullRequestWritePolicy());

        private PrCommandTask approvalTask(String commandText, String user) {
            PrCommandTask task = scopedTask(10L, PrCommandTaskType.CHAT, "PENDING");
            task.setCommandText(commandText);
            task.setCommentUserLogin(user);
            task.setCommentAuthorAssociation("COLLABORATOR");
            task.setCommentId(1001L);
            return task;
        }

        private PrCommandTask previewTask() {
            PrCommandTask task = scopedTask(20L, PrCommandTaskType.FIX_PREVIEW, "SUCCESS");
            task.setDryRun(true);
            task.setCommentUserLogin("reviewer");
            FixPatchArtifact artifact = codec.encode("diff --git a/A.java b/A.java\n", Set.of("A.java"));
            task.setPatchPayload(artifact.payload());
            task.setPatchSha256(artifact.sha256());
            task.setAllowedPathsJson(artifact.allowedPathsJson());
            return task;
        }

        private PrCommandTask publishTask() {
            PrCommandTask task = scopedTask(30L, PrCommandTaskType.FIX_PUBLISH, "SUCCESS");
            task.setApprovedBy("reviewer");
            task.setTargetBranch("codepilot/fix-20-abcd1234");
            task.setCreatedPrNumber(99);
            task.setCreatedPrUrl("https://github.com/liche719/codeAireview/pull/99");
            task.setCommitSha("fix-sha");
            return task;
        }

        private PrCommandTask scopedTask(Long id, String type, String status) {
            PrCommandTask task = new PrCommandTask();
            task.setId(id);
            task.setCommandType(type);
            task.setStatus(status);
            task.setRepoOwner("liche719");
            task.setRepoName("codeAireview");
            task.setPrNumber(12);
            task.setPrUrl("https://github.com/liche719/codeAireview/pull/12");
            task.setHeadSha("source-sha");
            return task;
        }

        private GithubPullRequestDetail sourcePullRequest() {
            GithubPullRequestDetail detail = new GithubPullRequestDetail();
            detail.setHeadSha("source-sha");
            detail.setHeadRef("feature/original");
            detail.setHeadRepoFullName("liche719/codeAireview");
            detail.setBaseRepoFullName("liche719/codeAireview");
            detail.setHeadRepoCloneUrl("https://github.com/liche719/codeAireview.git");
            return detail;
        }

        private static GithubCommandProperties properties() {
            GithubCommandProperties properties = new GithubCommandProperties();
            properties.setFixEnabled(true);
            properties.setFixPublishEnabled(true);
            properties.setFixMergeEnabled(true);
            return properties;
        }
    }
}
