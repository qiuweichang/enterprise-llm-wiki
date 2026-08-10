package com.llmwiki.review;

import com.llmwiki.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 暴露审核队列、详情、批准和驳回 API。
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {
    private final ReviewService reviewService;

    /** 创建审核控制器。 */
    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** 列出待审核变更。 */
    @GetMapping
    @RequiresPermission("REVIEW_READ")
    public List<ReviewService.ChangeSetSummary> list(@RequestParam(defaultValue = "50") int limit,
                                                     @RequestParam(required = false) UUID pageId) {
        return reviewService.listPending(limit, pageId);
    }

    /** 读取包含前后文本的审核详情。 */
    @GetMapping("/{changeSetId}")
    @RequiresPermission("REVIEW_READ")
    public ReviewService.ChangeSetDetail get(@PathVariable UUID changeSetId) {
        return reviewService.get(changeSetId);
    }

    /** 批准并合并变更集。 */
    @PostMapping("/{changeSetId}/approve")
    @RequiresPermission("REVIEW_APPROVE")
    public ReviewService.ReviewResult approve(@PathVariable UUID changeSetId,
                                              @RequestBody(required = false) ReviewDecision request) {
        return reviewService.approve(changeSetId, request == null ? "" : request.comment());
    }

    /** 驳回变更集并保留审计记录。 */
    @PostMapping("/{changeSetId}/reject")
    @RequiresPermission("REVIEW_APPROVE")
    public ReviewService.ReviewResult reject(@PathVariable UUID changeSetId, @RequestBody ReviewDecision request) {
        return reviewService.reject(changeSetId, request.comment());
    }

    /** 审核意见请求。 */
    public record ReviewDecision(String comment) { }
}
