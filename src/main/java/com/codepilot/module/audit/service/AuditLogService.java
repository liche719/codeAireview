package com.codepilot.module.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.module.audit.entity.AuditLog;
import com.codepilot.module.audit.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计写入与查询（2026-10-29）。
 *
 * <p><b>原则</b>：审计失败**不能影响主流程**。写日志本身出错只记一行 warn——
 * 为了一条审计记录把已经成功的审查变成失败，是把"可选的可追溯性"变成"新的失败点"。
 *
 * <p><b>记什么</b>：触发审查、批准修复、修改配置这三类动作，以及它们的来源（API Key 指纹 / 评论作者 / system）。
 * 不记正文、不记 token。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    public static final String ACTION_TRIGGER_REVIEW = "TRIGGER_REVIEW";

    public static final String ACTION_APPROVE_FIX = "APPROVE_FIX";

    public static final String ACTION_UPDATE_CONFIG = "UPDATE_CONFIG";

    private static final int DETAIL_MAX_CHARS = 1000;

    private final AuditLogMapper auditLogMapper;

    public void record(String action, String actor, String sourceIp, String target, String result, String detail) {
        try {
            AuditLog entry = new AuditLog();
            entry.setAction(trim(action, 48));
            entry.setActor(trim(actor, 128));
            entry.setSourceIp(trim(sourceIp, 64));
            entry.setTarget(trim(target, 256));
            entry.setResult(trim(result, 16));
            entry.setDetail(trim(detail, DETAIL_MAX_CHARS));
            entry.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(entry);
        } catch (RuntimeException exception) {
            log.warn("Audit log write failed, action={}, target={}, message={}", action, target, exception.getMessage());
        }
    }

    public List<AuditLog> recent(int days, String action, int limit) {
        int window = Math.max(1, Math.min(90, days));
        int size = Math.max(1, Math.min(500, limit));
        LambdaQueryWrapper<AuditLog> query = new LambdaQueryWrapper<AuditLog>()
                .ge(AuditLog::getCreatedAt, LocalDateTime.now().minusDays(window))
                .orderByDesc(AuditLog::getId)
                .last("LIMIT " + size);
        if (action != null && !action.isBlank()) {
            query.eq(AuditLog::getAction, action.trim().toUpperCase(java.util.Locale.ROOT));
        }
        return auditLogMapper.selectList(query);
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}
