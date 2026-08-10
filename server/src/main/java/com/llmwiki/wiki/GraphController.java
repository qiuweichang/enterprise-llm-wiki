package com.llmwiki.wiki;

import com.llmwiki.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 暴露知识图谱快照接口。
 */
@RestController
@RequestMapping("/api/graph")
public class GraphController {
    private final GraphService graphService;

    /** 创建图控制器。 */
    public GraphController(GraphService graphService) {
        this.graphService = graphService;
    }

    /** 返回当前空间图快照。 */
    @GetMapping
    @RequiresPermission("PAGE_READ")
    public GraphService.GraphSnapshot graph() {
        return graphService.load();
    }
}
