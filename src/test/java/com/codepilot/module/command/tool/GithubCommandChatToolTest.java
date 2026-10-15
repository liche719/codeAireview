package com.codepilot.module.command.tool;

import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.command.chat.ReviewSessionContextBuilder;
import com.codepilot.module.rag.dto.RuleSearchRecord;
import com.codepilot.module.rag.dto.RuleSearchResponse;
import com.codepilot.module.rag.service.RuleSearchService;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import com.codepilot.module.command.trace.AgentTaskTraceContext;
import com.codepilot.module.command.creator.PrCommandTaskCreator;
import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.approval.FixApprovalService;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import com.codepilot.module.review.dto.ReviewCreateResponse;
import com.codepilot.module.review.service.ReviewTaskService;
import com.codepilot.task.PrCommandTaskProducer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GithubCommandChatToolTest {

    @Test
    void shouldFormatPullRequestDetailForAgentConsumption() {
        GithubClient githubClient = mock(GithubClient.class);
        GithubPullRequestDetail detail = new GithubPullRequestDetail();
        detail.setTitle("Add webhook support");
        detail.setHtmlUrl("https://github.com/liche719/codeAireview/pull/12");
        detail.setHeadRef("feature/chat");
        detail.setHeadSha("abc123");
        detail.setHeadRepoFullName("liche719/codeAireview");
        detail.setBaseRepoFullName("liche719/codeAireview");
        when(githubClient.getPullRequestDetail("liche719", "codeAireview", 12)).thenReturn(detail);

        GithubCommandChatTool tool = new GithubCommandChatTool(githubClient);
        String result = tool.getPullRequestDetail("liche719", "codeAireview", 12);

        assertThat(result).contains("PR title: Add webhook support");
        assertThat(result).contains("PR url: https://github.com/liche719/codeAireview/pull/12");
        assertThat(result).contains("PR branch: feature/chat");
        assertThat(result).contains("head sha: abc123");
    }

    @Test
    void shouldFormatPullRequestFilesAndTruncatePatch() {
        GithubClient githubClient = mock(GithubClient.class);
        GithubChangedFile file = new GithubChangedFile();
        file.setFilename("src/main/java/com/codepilot/Demo.java");
        file.setStatus("modified");
        file.setAdditions(2);
        file.setDeletions(1);
        file.setChanges(3);
        file.setPatch("""
                @@ -1 +1,2 @@
                -old
                +new
                +more
                """);
        when(githubClient.listPullRequestFiles("liche719", "codeAireview", 12)).thenReturn(List.of(file));

        GithubCommandChatTool tool = new GithubCommandChatTool(githubClient);
        String result = tool.listPullRequestFiles("liche719", "codeAireview", 12);

        assertThat(result).contains("src/main/java/com/codepilot/Demo.java");
        assertThat(result).contains("(modified, +2, -1, changes=3)");
        assertThat(result).contains("patch: @@ -1 +1,2 @@ -old +new +more");
    }

    @Test
    void shouldBindNoArgumentGithubToolToCurrentPersistedTaskScope() {
        GithubClient githubClient = mock(GithubClient.class);
        PrCommandTaskMapper mapper = mock(PrCommandTaskMapper.class);
        when(mapper.selectById(77L)).thenReturn(agentTask());
        GithubPullRequestDetail detail = new GithubPullRequestDetail();
        detail.setTitle("Scoped PR");
        when(githubClient.getPullRequestDetail("liche719", "codeAireview", 12)).thenReturn(detail);
        GithubCommandChatTool tool = new GithubCommandChatTool(githubClient);
        tool.configureWorkflowTools(null, null, null, mapper, new GithubCommandProperties(), null);

        String result = AgentTaskTraceContext.run(77L, tool::getPullRequestDetail);

        assertThat(result).contains("Scoped PR");
        org.mockito.Mockito.verify(githubClient).getPullRequestDetail("liche719", "codeAireview", 12);
    }

    @Test
    void shouldExposeStoredReviewEvidenceAsAnAgentTool() {
        GithubClient githubClient = mock(GithubClient.class);
        ReviewSessionContextBuilder contextBuilder = mock(ReviewSessionContextBuilder.class);
        when(contextBuilder.build("liche719", "codeAireview", 12))
                .thenReturn("reviewSessionContextStatus: AVAILABLE\nhasSuccessfulReview: true\nlocation=src/App.java:12");

        GithubCommandChatTool tool = new GithubCommandChatTool(githubClient, contextBuilder, null);

        assertThat(tool.getStoredReviewFindings("liche719", "codeAireview", 12))
                .contains("reviewSessionContextStatus: AVAILABLE")
                .contains("location=src/App.java:12");
    }

    @Test
    void shouldDelegateTeamRuleSearchToExistingRagService() {
        GithubClient githubClient = mock(GithubClient.class);
        RuleSearchService ruleSearchService = mock(RuleSearchService.class);
        RuleSearchRecord record = new RuleSearchRecord();
        record.setType("SQL_RULE");
        record.setDistance(0.12D);
        record.setContent("禁止字符串拼接 SQL");
        when(ruleSearchService.search(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RuleSearchResponse(List.of(record)));
        GithubCommandChatTool tool = new GithubCommandChatTool(githubClient, null, ruleSearchService);

        String result = tool.searchTeamRules("SQL injection", "SQL_RULE");

        assertThat(result).contains("type=SQL_RULE").contains("禁止字符串拼接 SQL");
        org.mockito.ArgumentCaptor<com.codepilot.module.rag.dto.RuleSearchRequest> requestCaptor =
                org.mockito.ArgumentCaptor.forClass(com.codepilot.module.rag.dto.RuleSearchRequest.class);
        org.mockito.Mockito.verify(ruleSearchService).search(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getQuery()).isEqualTo("SQL injection");
        assertThat(requestCaptor.getValue().getType()).isEqualTo("SQL_RULE");
    }

    @Test
    void shouldRecordToolCallsAgainstCurrentAgentTask() {
        GithubClient githubClient = mock(GithubClient.class);
        PrCommandTaskLogService logService = mock(PrCommandTaskLogService.class);
        GithubPullRequestDetail detail = new GithubPullRequestDetail();
        detail.setTitle("Trace me");
        when(githubClient.getPullRequestDetail("liche719", "codeAireview", 12)).thenReturn(detail);
        GithubCommandChatTool tool = new GithubCommandChatTool(githubClient);
        tool.configureTraceLogging(logService);

        AgentTaskTraceContext.run(77L,
                () -> tool.getPullRequestDetail("liche719", "codeAireview", 12));

        org.mockito.Mockito.verify(logService).record(
                org.mockito.ArgumentMatchers.eq(77L),
                org.mockito.ArgumentMatchers.eq("TOOL_CALL"),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq("getPullRequestDetail"),
                org.mockito.ArgumentMatchers.contains("pullNumber=12"));
    }

    @Test
    void shouldLetAgentStartReviewWorkflowForCurrentPr() {
        GithubClient githubClient = mock(GithubClient.class);
        ReviewTaskService reviewTaskService = mock(ReviewTaskService.class);
        PrCommandTaskMapper mapper = mock(PrCommandTaskMapper.class);
        PrCommandTask agentTask = agentTask();
        when(mapper.selectById(77L)).thenReturn(agentTask);
        when(reviewTaskService.createTask(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ReviewCreateResponse(88L, "PENDING"));
        GithubCommandChatTool tool = new GithubCommandChatTool(githubClient);
        tool.configureWorkflowTools(reviewTaskService, null, null, mapper, new GithubCommandProperties(), null);

        String result = AgentTaskTraceContext.run(77L, tool::startReviewWorkflow);

        assertThat(result).contains("taskId=88").contains("status=PENDING");
    }

    @Test
    void shouldExposeScheduledReviewWorkflowToResumableAgentRuntime() {
        ReviewTaskService reviewTaskService = mock(ReviewTaskService.class);
        PrCommandTaskMapper mapper = mock(PrCommandTaskMapper.class);
        when(mapper.selectById(77L)).thenReturn(agentTask());
        when(reviewTaskService.createTask(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ReviewCreateResponse(88L, "PENDING"));
        GithubCommandChatTool tool = new GithubCommandChatTool(mock(GithubClient.class));
        tool.configureWorkflowTools(reviewTaskService, null, null, mapper, new GithubCommandProperties(), null);

        AgentTaskTraceContext.ExecutionResult<String> result = AgentTaskTraceContext.runTracked(
                77L, 9L, tool::startReviewWorkflow);

        assertThat(result.scheduledWorkflow()).isNotNull();
        assertThat(result.scheduledWorkflow().type()).isEqualTo("REVIEW");
        assertThat(result.scheduledWorkflow().taskId()).isEqualTo(88L);
    }

    @Test
    void shouldLetAgentStartDryRunFixPreviewWithoutPush() {
        GithubClient githubClient = mock(GithubClient.class);
        PrCommandTaskCreator creator = mock(PrCommandTaskCreator.class);
        PrCommandTaskProducer producer = mock(PrCommandTaskProducer.class);
        PrCommandTaskMapper mapper = mock(PrCommandTaskMapper.class);
        when(mapper.selectById(77L)).thenReturn(agentTask());
        PrCommandTask fixTask = new PrCommandTask();
        fixTask.setId(99L);
        fixTask.setStatus("PENDING");
        fixTask.setDryRun(true);
        when(creator.createFixTask(org.mockito.ArgumentMatchers.any())).thenReturn(fixTask);
        GithubCommandChatTool tool = new GithubCommandChatTool(githubClient);
        GithubCommandProperties properties = new GithubCommandProperties();
        properties.setFixEnabled(true);
        tool.configureWorkflowTools(null, creator, producer, mapper, properties, null);

        String result = AgentTaskTraceContext.run(77L, tool::startFixPreview);

        assertThat(result).contains("commandTaskId=99").contains("status=PENDING");
        org.mockito.ArgumentCaptor<com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload> payloadCaptor =
                org.mockito.ArgumentCaptor.forClass(com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload.class);
        org.mockito.Mockito.verify(creator).createFixTask(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().getDryRun()).isTrue();
        assertThat(payloadCaptor.getValue().getCommandText()).isEqualTo("只修复 SQL 拼接问题");
        org.mockito.Mockito.verify(producer).send(99L);
    }

    @Test
    void shouldRefuseFixPreviewWhenServerCapabilityIsDisabled() {
        GithubCommandChatTool tool = new GithubCommandChatTool(mock(GithubClient.class));
        GithubCommandProperties properties = new GithubCommandProperties();
        tool.configureWorkflowTools(null, mock(PrCommandTaskCreator.class),
                mock(PrCommandTaskProducer.class), mock(PrCommandTaskMapper.class), properties, null);

        String result = AgentTaskTraceContext.run(77L, tool::startFixPreview);

        assertThat(result).contains("disabled");
    }

    @Test
    void shouldEnqueueServerApprovedFixPublishTask() {
        FixApprovalService approvalService = mock(FixApprovalService.class);
        PrCommandTaskProducer producer = mock(PrCommandTaskProducer.class);
        PrCommandTask publishTask = new PrCommandTask();
        publishTask.setId(200L);
        publishTask.setStatus("PENDING");
        when(approvalService.approvePublish(77L, 99L)).thenReturn(publishTask);
        GithubCommandChatTool tool = new GithubCommandChatTool(mock(GithubClient.class));
        tool.configureWorkflowTools(null, null, producer, null,
                new GithubCommandProperties(), approvalService);

        String result = AgentTaskTraceContext.run(77L, () -> tool.approveFixPreview(99L));

        assertThat(result).contains("publishTaskId=200");
        org.mockito.Mockito.verify(producer).send(200L);
    }

    @Test
    void shouldEnqueueServerApprovedFixMergeTaskSeparately() {
        FixApprovalService approvalService = mock(FixApprovalService.class);
        PrCommandTaskProducer producer = mock(PrCommandTaskProducer.class);
        PrCommandTask mergeTask = new PrCommandTask();
        mergeTask.setId(300L);
        mergeTask.setStatus("PENDING");
        when(approvalService.approveMerge(77L, 200L)).thenReturn(mergeTask);
        GithubCommandChatTool tool = new GithubCommandChatTool(mock(GithubClient.class));
        tool.configureWorkflowTools(null, null, producer, null,
                new GithubCommandProperties(), approvalService);

        String result = AgentTaskTraceContext.run(77L, () -> tool.approveFixMerge(200L));

        assertThat(result).contains("mergeTaskId=300");
        org.mockito.Mockito.verify(producer).send(300L);
    }

    private PrCommandTask agentTask() {
        PrCommandTask task = new PrCommandTask();
        task.setId(77L);
        task.setRepoOwner("liche719");
        task.setRepoName("codeAireview");
        task.setPrNumber(12);
        task.setPrUrl("https://github.com/liche719/codeAireview/pull/12");
        task.setTitle("Improve agent tools");
        task.setHeadSha("abc123");
        task.setCommentId(1001L);
        task.setCommentBody("@x-pilotx 只修复 SQL 拼接问题");
        task.setCommandText("只修复 SQL 拼接问题");
        task.setCommentUserLogin("reviewer");
        return task;
    }
}
