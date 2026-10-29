package com.codepilot.module.audit.controller;

import com.codepilot.common.response.Result;
import com.codepilot.module.audit.entity.AuditLog;
import com.codepilot.module.audit.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审计查询（2026-10-29）。
 *
 * <p>企业部署里最常被问的三个问题：这个 PR 是谁触发的？这次合并是谁批准的？配置是谁改的？
 * 这个接口就是用来回答它们的。放在 {@code /api/**} 下，受项目统一的 API Key 过滤保护。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/audit")
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    public Result<List<AuditLog>> list(@RequestParam(defaultValue = "7") int days,
                                       @RequestParam(required = false) String action,
                                       @RequestParam(defaultValue = "100") int limit) {
        return Result.success(auditLogService.recent(days, action, limit));
    }
}
