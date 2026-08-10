package com.llmwiki.wiki;

import com.llmwiki.security.RequiresPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 暴露知识页面读取与变更提案 API。
 */
@RestController
@RequestMapping("/api/pages")
public class PageController {
    private final PageService pageService;

    /** 创建页面控制器。 */
    public PageController(PageService pageService) {
        this.pageService = pageService;
    }

    /**
     * 游标分页列出页面。
     */
    @GetMapping
    @RequiresPermission("PAGE_READ")
    public List<PageService.PageSummary> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant cursorUpdatedAt,
            @RequestParam(required = false) UUID cursorId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return pageService.list(cursorUpdatedAt, cursorId, limit);
    }

    /**
     * 读取一页完整知识及证据链。
     */
    @GetMapping("/{pageId}")
    @RequiresPermission("PAGE_READ")
    public PageService.PageDetail get(@PathVariable UUID pageId) {
        return pageService.get(pageId);
    }

    /**
     * 新建页面或提交更新；服务层根据目标是否存在和权限决定直接发布或送审。
     */
    @PostMapping
    @RequiresPermission("PAGE_CREATE")
    public PageService.MutationResult mutate(@Valid @RequestBody PageService.PageMutationRequest request) {
        return pageService.createOrPropose(request);
    }

    /** 提交删除知识页面的归档审核单；审核前不会改变已发布页面。 */
    @PostMapping("/{pageId}/archive")
    @RequiresPermission("PAGE_UPDATE_PROPOSE")
    public PageService.MutationResult archive(@PathVariable UUID pageId) {
        return pageService.proposeArchive(pageId);
    }

    /** 提交删除页面关联知识的审核单，服务端自动移除对应 WikiLink。 */
    @PostMapping("/{pageId}/relations/{relatedPageId}/remove")
    @RequiresPermission("PAGE_UPDATE_PROPOSE")
    public PageService.MutationResult removeRelation(@PathVariable UUID pageId, @PathVariable UUID relatedPageId) {
        return pageService.proposeRemoveRelation(pageId, relatedPageId);
    }
}
