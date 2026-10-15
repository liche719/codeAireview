package com.codepilot.module.command.approval;

import com.codepilot.module.command.entity.PrCommandTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class FixApprovalCommandRouter {

    private final FixApprovalCommandParser parser;

    private final FixApprovalService approvalService;

    public Optional<String> route(PrCommandTask approvalTask) {
        if (approvalTask == null || approvalTask.getId() == null) {
            return Optional.empty();
        }
        return parser.parse(approvalTask.getCommandText()).map(command -> switch (command.action()) {
            case PUBLISH -> publish(approvalTask.getId(), command.sourceTaskId());
            case MERGE -> merge(approvalTask.getId(), command.sourceTaskId());
        });
    }

    private String publish(Long approvalTaskId, Long previewTaskId) {
        try {
            PrCommandTask task = approvalService.approvePublish(approvalTaskId, previewTaskId);
            return "修复发布任务已通过服务端审批并进入队列：publishTaskId=" + task.getId()
                    + "，status=" + task.getStatus();
        } catch (FixApprovalException exception) {
            return "修复发布审批被拒绝：" + exception.getMessage();
        }
    }

    private String merge(Long approvalTaskId, Long publishTaskId) {
        try {
            PrCommandTask task = approvalService.approveMerge(approvalTaskId, publishTaskId);
            return "修复合并任务已通过服务端审批并进入队列：mergeTaskId=" + task.getId()
                    + "，status=" + task.getStatus();
        } catch (FixApprovalException exception) {
            return "修复合并审批被拒绝：" + exception.getMessage();
        }
    }
}
