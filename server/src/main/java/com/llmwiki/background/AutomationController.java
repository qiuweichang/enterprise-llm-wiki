package com.llmwiki.background;

import com.llmwiki.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 暴露空间级持续演进策略配置。
 */
@RestController
@RequestMapping("/api/automation")
public class AutomationController {
    private final AutomationSettingsService settingsService;
    private final ModelSettingsService modelSettingsService;
    private final ModelConnectionTestService modelConnectionTestService;
    private final EvolutionRunService evolutionRunService;
    private final AiScheduledTaskService aiScheduledTaskService;

    /** 创建自动化控制器。 */
    public AutomationController(AutomationSettingsService settingsService, ModelSettingsService modelSettingsService,
                                ModelConnectionTestService modelConnectionTestService,
                                EvolutionRunService evolutionRunService,
                                AiScheduledTaskService aiScheduledTaskService) {
        this.settingsService = settingsService;
        this.modelSettingsService = modelSettingsService;
        this.modelConnectionTestService = modelConnectionTestService;
        this.evolutionRunService = evolutionRunService;
        this.aiScheduledTaskService = aiScheduledTaskService;
    }

    /** 读取设置。 */
    @GetMapping("/settings")
    @RequiresPermission("AUTOMATION_READ")
    public AutomationSettingsService.Settings get() {
        return settingsService.get();
    }

    /** 更新设置。 */
    @PutMapping("/settings")
    @RequiresPermission("AUTOMATION_MANAGE")
    public AutomationSettingsService.Settings update(@RequestBody AutomationSettingsService.Settings settings) {
        return settingsService.update(settings);
    }

    /** 读取当前空间脱敏模型配置。 */
    @GetMapping("/model")
    @RequiresPermission("MODEL_MANAGE")
    public ModelSettingsService.ModelSettingsView model() {
        return modelSettingsService.get();
    }

    /** 保存当前空间模型配置。 */
    @PutMapping("/model")
    @RequiresPermission("MODEL_MANAGE")
    public ModelSettingsService.ModelSettingsView updateModel(
            @RequestBody ModelSettingsService.ModelSettingsRequest request) {
        return modelSettingsService.update(request);
    }

    /** 测试尚未保存的模型配置，成功后相同配置才允许保存。 */
    @PostMapping("/model/test")
    @RequiresPermission("MODEL_MANAGE")
    public ModelConnectionTestService.TestView testModel(
            @RequestBody ModelSettingsService.ModelSettingsRequest request) {
        return modelConnectionTestService.test(request);
    }

    /** 列出持续优化运行记录和关联审核状态。 */
    @GetMapping("/runs")
    @RequiresPermission("AUTOMATION_READ")
    public List<EvolutionRunService.RunView> runs(@RequestParam(defaultValue = "30") int limit) {
        return evolutionRunService.list(limit);
    }

    /** 立即创建一轮持续优化运行。 */
    @PostMapping("/runs")
    @RequiresPermission("AUTOMATION_MANAGE")
    public Map<String, UUID> triggerRun() {
        return Map.of("runId", evolutionRunService.triggerNow());
    }

    /** 查询当前空间的 AI 定时任务定义。 */
    @GetMapping("/tasks")
    @RequiresPermission("AUTOMATION_READ")
    public List<AiScheduledTaskService.TaskView> tasks() {
        return aiScheduledTaskService.listTasks();
    }

    /** 创建 AI 定时任务。 */
    @PostMapping("/tasks")
    @RequiresPermission("AUTOMATION_MANAGE")
    public AiScheduledTaskService.TaskView createTask(@RequestBody AiScheduledTaskService.TaskRequest request) {
        return aiScheduledTaskService.create(request);
    }

    /** 更新 AI 定时任务，包括启停、频率和查询指令。 */
    @PutMapping("/tasks/{taskId}")
    @RequiresPermission("AUTOMATION_MANAGE")
    public AiScheduledTaskService.TaskView updateTask(@PathVariable UUID taskId,
                                                       @RequestBody AiScheduledTaskService.TaskRequest request) {
        return aiScheduledTaskService.update(taskId, request);
    }

    /** 删除 AI 定时任务及其历史执行记录。 */
    @org.springframework.web.bind.annotation.DeleteMapping("/tasks/{taskId}")
    @RequiresPermission("AUTOMATION_MANAGE")
    public void deleteTask(@PathVariable UUID taskId) {
        aiScheduledTaskService.delete(taskId);
    }

    /** 手动排队执行一个 AI 定时任务。 */
    @PostMapping("/tasks/{taskId}/run")
    @RequiresPermission("AUTOMATION_MANAGE")
    public Map<String, UUID> runTask(@PathVariable UUID taskId) {
        return Map.of("runId", aiScheduledTaskService.trigger(taskId));
    }

    /** 查询 AI 定时任务的执行状态和结果记录。 */
    @GetMapping("/task-runs")
    @RequiresPermission("AUTOMATION_READ")
    public List<AiScheduledTaskService.RunView> taskRuns(@RequestParam(defaultValue = "100") int limit) {
        return aiScheduledTaskService.listRuns(limit);
    }
}
