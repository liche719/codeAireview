package com.codepilot.module.git.client;

import com.codepilot.module.git.dto.GithubReviewThread;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 解析 GitHub GraphQL 的 {@code reviewThreads} 响应。
 *
 * <p>拆成独立的类是为了能在没有网络的情况下单测解析逻辑——响应结构嵌套较深，
 * 出错方式又很安静（拿到空列表而不是报错）。</p>
 */
class GithubReviewThreadResolver {

    /** GraphQL 里 reviewThreads 默认一页 100 条；超过部分本版不追页。 */
    static final int PAGE_SIZE = 100;

    List<GithubReviewThread> fromGraphqlResponse(Map<String, Object> response) {
        List<GithubReviewThread> threads = new ArrayList<>();
        for (Object node : threadNodes(response)) {
            if (!(node instanceof Map<?, ?> thread)) {
                continue;
            }
            threads.add(new GithubReviewThread(
                    text(thread.get("id")),
                    Boolean.TRUE.equals(thread.get("isResolved")),
                    firstCommentText(thread, "author", "login"),
                    firstCommentText(thread, "body")
            ));
        }
        return threads;
    }

    private List<?> threadNodes(Map<String, Object> response) {
        if (response == null) {
            return List.of();
        }
        Object data = response.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return List.of();
        }
        Object repository = dataMap.get("repository");
        if (!(repository instanceof Map<?, ?> repositoryMap)) {
            return List.of();
        }
        Object pullRequest = repositoryMap.get("pullRequest");
        if (!(pullRequest instanceof Map<?, ?> pullRequestMap)) {
            return List.of();
        }
        Object reviewThreads = pullRequestMap.get("reviewThreads");
        if (!(reviewThreads instanceof Map<?, ?> reviewThreadsMap)) {
            return List.of();
        }
        Object nodes = reviewThreadsMap.get("nodes");
        return nodes instanceof List<?> list ? list : List.of();
    }

    /**
     * 取线程首条评论的某个字段。只取首条是刻意的——
     * 判断"这个线程是不是我们开的"必须看发起者，而不是回复者。
     */
    private String firstCommentText(Map<?, ?> thread, String... path) {
        Object comments = thread.get("comments");
        if (!(comments instanceof Map<?, ?> commentsMap)) {
            return null;
        }
        Object nodes = commentsMap.get("nodes");
        if (!(nodes instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Object current = list.getFirst();
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current == null ? null : String.valueOf(current);
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
