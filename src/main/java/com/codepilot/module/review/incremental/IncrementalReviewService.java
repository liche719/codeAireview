package com.codepilot.module.review.incremental;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codepilot.module.review.entity.ReviewFile;
import com.codepilot.module.review.entity.ReviewTask;
import com.codepilot.module.review.mapper.ReviewFileMapper;
import com.codepilot.module.review.mapper.ReviewTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 增量审查（2026-10-30）：和上一次审查相比 **patch 完全没变的文件跳过不审**。
 *
 * <p><b>为什么需要</b>：真实 PR 会反复推，目前每次都是全量重审——同样的文件花同样的 token、
 * 同样的时间，而且大概率得出同样的结论。用户的感受是"又慢又吵"。
 *
 * <p><b>为什么比较 patch 而不是比较 commit diff</b>：不依赖 GitHub compare API（少一次调用、
 * 少一个失败点），而且语义更准——只有**这个文件的内容真的变了**才需要重审；
 * rebase 导致的提交变化但文件没变，不该触发重审。
 *
 * <p><b>边界</b>：只在"同一个 PR 有上一次成功审查"时生效；没有历史就照常全量审。
 * 被跳过的文件会落 `skip_reason`，摘要里的覆盖度声明会照实体现，不会被读成"全部通过"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncrementalReviewService {

    private static final String SKIP_CATEGORY = "UNCHANGED";

    private final ReviewTaskMapper reviewTaskMapper;

    private final ReviewFileMapper reviewFileMapper;

    @Value("${codepilot.review.incremental-enabled:true}")
    private boolean enabled = true;

    /**
     * 把"和上次审查相比没有任何变化"的文件标记为跳过。
     *
     * @param files 本次 PR 的变更文件（会被就地修改）
     * @return 跳过的文件数；0 表示本次是全量审
     */
    public int markUnchangedAsSkipped(ReviewTask task, List<ReviewFile> files) {
        if (!enabled || task == null || files == null || files.isEmpty()) {
            return 0;
        }
        Map<String, String> previousPatches = previousPatches(task);
        if (previousPatches.isEmpty()) {
            return 0;
        }
        int skipped = 0;
        for (ReviewFile file : files) {
            String previous = previousPatches.get(file.getFilePath());
            if (previous != null && previous.equals(file.getPatch())) {
                file.setSkipped(true);
                file.setSkipCategory(SKIP_CATEGORY);
                file.setSkipReason("与上次审查相比该文件无变化（增量审查）");
                skipped++;
            }
        }
        if (skipped > 0) {
            log.info("Incremental review skipped unchanged files, taskId={}, skipped={}, total={}",
                    task.getId(), skipped, files.size());
        }
        return skipped;
    }

    /** 同一个 PR 最近一次「成功且 headSha 不同」的审查里，每个文件的 patch */
    private Map<String, String> previousPatches(ReviewTask task) {
        if (task.getRepoOwner() == null || task.getRepoName() == null || task.getPrNumber() == null) {
            return Map.of();
        }
        List<ReviewTask> candidates = reviewTaskMapper.selectList(new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getRepoOwner, task.getRepoOwner())
                .eq(ReviewTask::getRepoName, task.getRepoName())
                .eq(ReviewTask::getPrNumber, task.getPrNumber())
                .eq(ReviewTask::getStatus, "SUCCESS")
                .ne(task.getHeadSha() != null, ReviewTask::getHeadSha, task.getHeadSha())
                .lt(ReviewTask::getId, task.getId())
                .orderByDesc(ReviewTask::getId)
                .last("LIMIT 1"));
        if (candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        Long previousTaskId = candidates.getFirst().getId();
        List<ReviewFile> previousFiles = reviewFileMapper.selectList(new LambdaQueryWrapper<ReviewFile>()
                .eq(ReviewFile::getTaskId, previousTaskId));
        Map<String, String> patches = new HashMap<>();
        for (ReviewFile file : previousFiles) {
            if (file.getFilePath() != null && file.getPatch() != null) {
                patches.put(file.getFilePath(), file.getPatch());
            }
        }
        return patches;
    }
}
