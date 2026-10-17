package com.codepilot.module.command.config;

import com.codepilot.module.command.git.ValidationExecutionMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "codepilot.github")
public class GithubCommandProperties {

    private List<String> botMentionAliases = new ArrayList<>(List.of("@x-pilotx", "@X-PilotX"));

    private List<String> allowedCommentAuthorAssociations = new ArrayList<>(List.of(
            "OWNER",
            "MEMBER",
            "COLLABORATOR"
    ));

    private boolean fixEnabled = false;

    /**
     * 是否处理 diff 行评论（{@code pull_request_review_comment}）里的 @mention（R14）。
     *
     * <p>默认开启：这是纯读侧能力，只是让"在问题代码旁边提问"这个最自然的入口可用。
     * 关闭后该事件回到被忽略的状态。</p>
     */
    private boolean reviewCommentMentionEnabled = true;

    private boolean fixPublishEnabled = false;

    private boolean fixMergeEnabled = false;

    private boolean fixApprovalRequireSameUser = true;

    private int fixMaxPatchChars = 100000;

    private String fixBranchPrefix = "codepilot/fix-";

    private String fixMergeMethod = "squash";

    private int fixMaxFiles = 3;

    private int fixMaxChangedLines = 120;

    private String fixValidationCommand = "git diff --check";

    private List<String> fixAllowedValidationCommands = new ArrayList<>(List.of("git diff --check"));

    private boolean fixValidationAllowBuildCommands = false;

    private boolean fixValidationInheritEnvironment = false;

    private int fixValidationTimeoutSeconds = 300;

    private ValidationExecutionMode fixValidationExecutionMode = ValidationExecutionMode.LOCAL;

    private String fixValidationDockerImage = "";

    private String fixValidationDockerNetwork = "none";
}
