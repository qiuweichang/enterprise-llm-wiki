package com.llmwiki.dashboard;

import com.llmwiki.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 暴露企业工作台聚合接口。
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final DashboardService dashboardService;

    /** 创建工作台控制器。 */
    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** 一次请求返回工作台完整快照。 */
    @GetMapping
    @RequiresPermission("PAGE_READ")
    public DashboardService.DashboardSnapshot dashboard() {
        return dashboardService.load();
    }
}

