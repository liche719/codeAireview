package com.codepilot.module.review.cost;

/**
 * 当前正在审查的任务 id（2026-10-29）。
 *
 * <p><b>为什么需要它</b>：LLM 调用的最底层（`LangChain4jReviewLlmClient`）只拿得到 model 与 token，
 * 拿不到 task/repo。要做"按仓库统计花费"，最省事的办法是在**文件级审查**这个边界上放一个
 * ThreadLocal，让底层在落库时顺手带上 taskId；事后 `JOIN review_task` 就能得到仓库。
 *
 * <p>用 ThreadLocal 而不是改一长串方法签名，是因为这条调用链有七八层，
 * 而 taskId 是"当前正在做什么"的上下文，本来就适合放在这一层。
 */
public final class ReviewCostContext {

    private static final ThreadLocal<Long> CURRENT_TASK = new ThreadLocal<>();

    private ReviewCostContext() {
    }

    public static void setTaskId(Long taskId) {
        if (taskId == null) {
            CURRENT_TASK.remove();
        } else {
            CURRENT_TASK.set(taskId);
        }
    }

    public static Long currentTaskId() {
        return CURRENT_TASK.get();
    }

    public static void clear() {
        CURRENT_TASK.remove();
    }
}
