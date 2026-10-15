package com.codepilot.module.command.tool;

import com.codepilot.module.git.client.GithubClient;
import com.codepilot.module.git.dto.GithubChangedFile;
import com.codepilot.module.git.dto.GithubPullRequestDetail;
import com.codepilot.module.command.chat.ReviewSessionContextBuilder;
import com.codepilot.module.rag.dto.RuleSearchRecord;
import com.codepilot.module.rag.dto.RuleSearchRequest;
import com.codepilot.module.rag.dto.RuleSearchResponse;
import com.codepilot.module.rag.service.RuleSearchService;
import com.codepilot.infrastructure.search.SearxngSearchService;
import com.codepilot.infrastructure.search.AgentWebResourceService;
import com.codepilot.infrastructure.search.WebDocumentFetchService;
import com.codepilot.module.command.service.PrCommandTaskLogService;
import com.codepilot.module.command.trace.AgentTaskTraceContext;
import com.codepilot.common.enums.ReviewCommentMode;
import com.codepilot.module.command.creator.PrCommandTaskCreator;
import com.codepilot.module.command.config.GithubCommandProperties;
import com.codepilot.module.command.approval.FixApprovalException;
import com.codepilot.module.command.approval.FixApprovalService;
import com.codepilot.module.command.queue.PrCommandTaskMessageDispatcher;
import com.codepilot.module.command.entity.PrCommandTask;
import com.codepilot.module.command.mapper.PrCommandTaskMapper;
import com.codepilot.module.github.webhook.GitHubPullRequestWebhookPayload;
import com.codepilot.module.review.dto.ReviewCreateResponse;
import com.codepilot.module.review.service.ReviewTaskService;
import com.codepilot.task.PrCommandTaskProducer;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class GithubCommandChatTool {

    private static final int MAX_FILES = 10;

    private static final int MAX_PATCH_CHARS_PER_FILE = 800;

    private static final int MAX_CONTEXT_CHARS = 6000;

    private final GithubClient githubClient;

    private final ReviewSessionContextBuilder reviewSessionContextBuilder;

    private final RuleSearchService ruleSearchService;

    private final SearxngSearchService searxngSearchService;

    private PrCommandTaskLogService commandTaskLogService;

    private ReviewTaskService reviewTaskService;

    private PrCommandTaskCreator commandTaskCreator;

    private com.codepilot.module.command.service.PrCommandTaskService commandTaskService;

    private PrCommandTaskProducer commandTaskProducer;

    private PrCommandTaskMapper commandTaskMapper;

    private GithubCommandProperties commandProperties;

    private FixApprovalService fixApprovalService;

    private PrCommandTaskMessageDispatcher messageDispatcher;

    private AgentWebResourceService webResourceService;

    private WebDocumentFetchService webDocumentFetchService;

    @Autowired(required = false)
    public void configureTraceLogging(PrCommandTaskLogService commandTaskLogService) {
        this.commandTaskLogService = commandTaskLogService;
    }

    @Autowired(required = false)
    public void configureWorkflowTools(
            ReviewTaskService reviewTaskService,
            PrCommandTaskCreator commandTaskCreator,
            PrCommandTaskProducer commandTaskProducer,
            PrCommandTaskMapper commandTaskMapper,
            GithubCommandProperties commandProperties,
            FixApprovalService fixApprovalService
    ) {
        this.reviewTaskService = reviewTaskService;
        this.commandTaskCreator = commandTaskCreator;
        this.commandTaskProducer = commandTaskProducer;
        this.commandTaskMapper = commandTaskMapper;
        this.commandProperties = commandProperties;
        this.fixApprovalService = fixApprovalService;
    }

    @Autowired(required = false)
    public void configureCommandTaskService(com.codepilot.module.command.service.PrCommandTaskService commandTaskService) {
        this.commandTaskService = commandTaskService;
    }

    @Autowired(required = false)
    public void configureMessageDispatcher(PrCommandTaskMessageDispatcher messageDispatcher) {
        this.messageDispatcher = messageDispatcher;
    }

    @Autowired(required = false)
    public void configureWebResearchTools(
            AgentWebResourceService webResourceService,
            WebDocumentFetchService webDocumentFetchService
    ) {
        this.webResourceService = webResourceService;
        this.webDocumentFetchService = webDocumentFetchService;
    }

    @Autowired
    public GithubCommandChatTool(
            GithubClient githubClient,
            ReviewSessionContextBuilder reviewSessionContextBuilder,
            RuleSearchService ruleSearchService,
            SearxngSearchService searxngSearchService
    ) {
        this.githubClient = githubClient;
        this.reviewSessionContextBuilder = reviewSessionContextBuilder;
        this.ruleSearchService = ruleSearchService;
        this.searxngSearchService = searxngSearchService;
    }

    // Keeps the small unit-test constructor and makes the optional tools explicit.
    public GithubCommandChatTool(GithubClient githubClient) {
        this(githubClient, null, null, null);
    }

    public GithubCommandChatTool(
            GithubClient githubClient,
            ReviewSessionContextBuilder reviewSessionContextBuilder,
            RuleSearchService ruleSearchService
    ) {
        this(githubClient, reviewSessionContextBuilder, ruleSearchService, null);
    }

    @Tool("Get the current GitHub PR basic info from the server-bound task scope")
    public String getPullRequestDetail() {
        PrCommandTask task = currentAgentTask();
        if (task == null) {
            return "Current pull request scope is unavailable.";
        }
        return getPullRequestDetail(task.getRepoOwner(), task.getRepoName(), task.getPrNumber());
    }

    public String getPullRequestDetail(
            @P("repository owner") String owner,
            @P("repository name") String repo,
            @P("PR number") Integer pullNumber
    ) {
        traceTool("getPullRequestDetail", "owner=" + owner + ", repo=" + repo + ", pullNumber=" + pullNumber);
        GithubPullRequestDetail detail = githubClient.getPullRequestDetail(owner, repo, pullNumber);
        StringBuilder context = new StringBuilder();
        appendLine(context, "PR title: " + safeText(detail.getTitle(), "N/A"));
        appendLine(context, "PR url: " + safeText(detail.getHtmlUrl(), buildFallbackPrUrl(owner, repo, pullNumber)));
        appendLine(context, "PR branch: " + safeText(detail.getHeadRef(), "N/A"));
        appendLine(context, "head sha: " + safeText(detail.getHeadSha(), "N/A"));
        appendLine(context, "head repo: " + safeText(detail.getHeadRepoFullName(), "N/A"));
        appendLine(context, "base repo: " + safeText(detail.getBaseRepoFullName(), "N/A"));
        return truncate(context.toString(), MAX_CONTEXT_CHARS);
    }

    @Tool("Get changed files and patch summaries for the server-bound current pull request")
    public String listPullRequestFiles() {
        PrCommandTask task = currentAgentTask();
        if (task == null) {
            return "Current pull request scope is unavailable.";
        }
        return listPullRequestFiles(task.getRepoOwner(), task.getRepoName(), task.getPrNumber());
    }

    public String listPullRequestFiles(
            @P("repository owner") String owner,
            @P("repository name") String repo,
            @P("PR number") Integer pullNumber
    ) {
        traceTool("listPullRequestFiles", "owner=" + owner + ", repo=" + repo + ", pullNumber=" + pullNumber);
        List<GithubChangedFile> files = githubClient.listPullRequestFiles(owner, repo, pullNumber);
        StringBuilder context = new StringBuilder();
        if (files != null) {
            int count = 0;
            for (GithubChangedFile file : files) {
                if (count >= MAX_FILES || context.length() >= MAX_CONTEXT_CHARS) {
                    break;
                }
                count++;
                appendLine(context, "- " + safeText(file.getFilename(), "N/A")
                        + " (" + safeText(file.getStatus(), "N/A")
                        + ", +" + defaultNumber(file.getAdditions())
                        + ", -" + defaultNumber(file.getDeletions())
                        + ", changes=" + defaultNumber(file.getChanges()) + ")");
                String patch = truncate(file.getPatch(), MAX_PATCH_CHARS_PER_FILE);
                if (StringUtils.hasText(patch)) {
                    appendLine(context, "  patch: " + patch.replace("\n", " "));
                }
            }
            if (files.size() > MAX_FILES) {
                appendLine(context, "- ... and " + (files.size() - MAX_FILES) + " more files");
            }
        }
        return truncate(context.toString(), MAX_CONTEXT_CHARS);
    }

    @Tool("Get stored review findings for the server-bound current pull request")
    public String getStoredReviewFindings() {
        PrCommandTask task = currentAgentTask();
        if (task == null) {
            return "Current pull request scope is unavailable.";
        }
        return getStoredReviewFindings(task.getRepoOwner(), task.getRepoName(), task.getPrNumber());
    }

    public String getStoredReviewFindings(
            @P("repository owner") String owner,
            @P("repository name") String repo,
            @P("PR number") Integer pullNumber
    ) {
        traceTool("getStoredReviewFindings", "owner=" + owner + ", repo=" + repo + ", pullNumber=" + pullNumber);
        if (reviewSessionContextBuilder == null) {
            return "Stored review findings are unavailable.";
        }
        String context = reviewSessionContextBuilder.build(owner, repo, pullNumber);
        return truncate(context, MAX_CONTEXT_CHARS);
    }

    @Tool("Search the indexed team coding rules relevant to a review question")
    public String searchTeamRules(
            @P("review question or code risk to search for") String query,
            @P("optional rule type, such as SQL_RULE or SECURITY_RULE") String type
    ) {
        traceTool("searchTeamRules", "queryLength=" + (query == null ? 0 : query.length()) + ", type=" + type);
        if (ruleSearchService == null || !StringUtils.hasText(query)) {
            return "Team rule search is unavailable.";
        }
        RuleSearchRequest request = new RuleSearchRequest();
        request.setQuery(query);
        request.setTopK(5);
        request.setType(StringUtils.hasText(type) ? type : null);
        RuleSearchResponse response = ruleSearchService.search(request);
        if (response == null || response.getRecords() == null || response.getRecords().isEmpty()) {
            return "No matching indexed team rules were found.";
        }
        StringBuilder context = new StringBuilder("Indexed team rules:\n");
        for (RuleSearchRecord record : response.getRecords()) {
            if (record == null || !StringUtils.hasText(record.getContent())) {
                continue;
            }
            appendLine(context, "- type=" + safeText(record.getType(), "UNKNOWN")
                    + ", distance=" + (record.getDistance() == null ? "N/A" : record.getDistance())
                    + ", content=" + truncate(record.getContent(), 900));
        }
        return truncate(context.toString(), MAX_CONTEXT_CHARS);
    }

    @Tool("Search the public web through the configured SearXNG instance for external technical references")
    public String searchWeb(
            @P("technical question or concept to search") String query
    ) {
        traceTool("searchWeb", "queryLength=" + (query == null ? 0 : query.length()));
        if (searxngSearchService == null) {
            return "Web search is unavailable.";
        }
        String rawResult = searxngSearchService.search(query);
        if (webResourceService != null) {
            webResourceService.registerSearchResults(AgentTaskTraceContext.currentAgentRunId(), rawResult);
        }
        return truncate(rawResult, MAX_CONTEXT_CHARS);
    }

    @Tool("Fetch readable text for a URL returned by searchWeb in this Agent run; only server-approved HTTPS domains are allowed")
    public String fetchWebDocument(@P("an exact HTTPS URL previously returned by searchWeb") String url) {
        traceTool("fetchWebDocument", "urlLength=" + (url == null ? 0 : url.length()));
        Long runId = AgentTaskTraceContext.currentAgentRunId();
        if (webResourceService == null || webDocumentFetchService == null || !webResourceService.isRegistered(runId, url)) {
            return "Web document fetch refused because this URL was not returned by searchWeb in the current Agent run.";
        }
        return truncate(webDocumentFetchService.fetch(url), MAX_CONTEXT_CHARS);
    }

    @Tool("Start the predefined asynchronous review workflow for the current PR when the user asks to run or rerun a review")
    public String startReviewWorkflow() {
        traceTool("startReviewWorkflow", "current PR");
        if (!AgentTaskTraceContext.canScheduleWorkflow()) {
            return "Another asynchronous workflow is already scheduled for this Agent turn.";
        }
        PrCommandTask agentTask = currentAgentTask();
        if (agentTask == null || reviewTaskService == null) {
            return "Review workflow is unavailable.";
        }
        ReviewCreateResponse response = reviewTaskService.createTask(
                agentTask.getPrUrl(),
                agentTask.getTitle(),
                ReviewCommentMode.SUMMARY_ONLY,
                agentTask.getHeadSha());
        if (!isTerminalStatus(response.getStatus())) {
            AgentTaskTraceContext.scheduleWorkflow("REVIEW", response.getTaskId());
        }
        return "Review workflow accepted: taskId=" + response.getTaskId() + ", status=" + response.getStatus();
    }

    @Tool("Create an asynchronous dry-run fix preview for the current PR based on the user's specific request; this never pushes code")
    public String startFixPreview() {
        traceTool("startFixPreview", "dryRun=true");
        if (!AgentTaskTraceContext.canScheduleWorkflow()) {
            return "Another asynchronous workflow is already scheduled for this Agent turn.";
        }
        if (commandProperties == null || !commandProperties.isFixEnabled()) {
            return "Fix preview is disabled by server configuration.";
        }
        PrCommandTask agentTask = currentAgentTask();
        if (agentTask == null || commandTaskCreator == null || commandTaskProducer == null) {
            return "Fix preview is unavailable.";
        }
        GitHubPullRequestWebhookPayload payload = new GitHubPullRequestWebhookPayload();
        payload.setOwner(agentTask.getRepoOwner());
        payload.setRepo(agentTask.getRepoName());
        payload.setPullNumber(agentTask.getPrNumber());
        payload.setPrUrl(agentTask.getPrUrl());
        payload.setTitle(agentTask.getTitle());
        payload.setHeadSha(agentTask.getHeadSha());
        payload.setCommentId(agentTask.getCommentId());
        payload.setCommentBody(agentTask.getCommentBody());
        payload.setCommandText(agentTask.getCommandText());
        payload.setCommentUserLogin(agentTask.getCommentUserLogin());
        payload.setCommentAuthorAssociation(agentTask.getCommentAuthorAssociation());
        payload.setDryRun(true);
        PrCommandTask fixTask = commandTaskService != null
                ? commandTaskService.createFixTask(payload)
                : commandTaskCreator.createFixTask(payload);
        if (!Boolean.TRUE.equals(fixTask.getDryRun())) {
            return "Fix preview refused because an existing write-enabled task is bound to this comment.";
        }
        if (!"SUCCESS".equalsIgnoreCase(fixTask.getStatus()) && !"FAILED".equalsIgnoreCase(fixTask.getStatus())) {
            if (commandTaskService == null) {
                commandTaskProducer.send(fixTask.getId());
            }
            AgentTaskTraceContext.scheduleWorkflow("FIX_PREVIEW", fixTask.getId());
        }
        return "Fix preview accepted: commandTaskId=" + fixTask.getId() + ", status=" + fixTask.getStatus();
    }

    @Tool("Approve publishing an already validated fix preview to a new bot branch and fix pull request; server-side exact user approval is mandatory")
    public String approveFixPreview(
            @P("the exact fix preview task id explicitly approved by the user") Long previewTaskId
    ) {
        traceTool("approveFixPreview", "previewTaskId=" + previewTaskId);
        Long approvalTaskId = AgentTaskTraceContext.currentTaskId();
        if (approvalTaskId == null || fixApprovalService == null || commandTaskProducer == null) {
            return "Fix publishing approval is unavailable.";
        }
        try {
            PrCommandTask publishTask = fixApprovalService.approvePublish(approvalTaskId, previewTaskId);
            if (messageDispatcher == null && commandTaskProducer != null && !isTerminalStatus(publishTask.getStatus())) {
                commandTaskProducer.send(publishTask.getId());
            }
            return "Fix publishing accepted: publishTaskId=" + publishTask.getId()
                    + ", status=" + publishTask.getStatus();
        } catch (FixApprovalException exception) {
            return "Fix publishing refused: " + exception.getMessage();
        }
    }

    @Tool("Approve merging a completed fix pull request; server-side exact user approval and merge safety checks are mandatory")
    public String approveFixMerge(
            @P("the exact successful fix publish task id explicitly approved by the user") Long publishTaskId
    ) {
        traceTool("approveFixMerge", "publishTaskId=" + publishTaskId);
        Long approvalTaskId = AgentTaskTraceContext.currentTaskId();
        if (approvalTaskId == null || fixApprovalService == null || commandTaskProducer == null) {
            return "Fix merge approval is unavailable.";
        }
        try {
            PrCommandTask mergeTask = fixApprovalService.approveMerge(approvalTaskId, publishTaskId);
            if (messageDispatcher == null && commandTaskProducer != null && !isTerminalStatus(mergeTask.getStatus())) {
                commandTaskProducer.send(mergeTask.getId());
            }
            return "Fix merge accepted: mergeTaskId=" + mergeTask.getId()
                    + ", status=" + mergeTask.getStatus();
        } catch (FixApprovalException exception) {
            return "Fix merge refused: " + exception.getMessage();
        }
    }

    private PrCommandTask currentAgentTask() {
        Long taskId = AgentTaskTraceContext.currentTaskId();
        return taskId == null || commandTaskMapper == null ? null : commandTaskMapper.selectById(taskId);
    }

    private boolean isTerminalStatus(String status) {
        return "SUCCESS".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status);
    }

    private void traceTool(String toolName, String detail) {
        Long taskId = AgentTaskTraceContext.currentTaskId();
        if (taskId != null && commandTaskLogService != null) {
            commandTaskLogService.record(taskId, "TOOL_CALL", true, toolName, detail);
        }
    }

    private void appendLine(StringBuilder builder, String line) {
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(line);
    }

    private String safeText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private int defaultNumber(Integer value) {
        return value == null ? 0 : value;
    }

    private String buildFallbackPrUrl(String owner, String repo, Integer pullNumber) {
        return "https://github.com/" + safeText(owner, "unknown") + "/" + safeText(repo, "unknown") + "/pull/" + pullNumber;
    }

    private String truncate(String content, int maxLength) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }
}
