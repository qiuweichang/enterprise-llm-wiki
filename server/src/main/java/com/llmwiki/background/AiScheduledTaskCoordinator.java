package com.llmwiki.background;

import com.llmwiki.config.LlmWikiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

/**
 * 扫描并执行 AI 定时资料任务。
 * 调度事务只负责排队和领取，模型网络调用交给虚拟线程，避免长时间占用数据库事务。
 */
@Component
public class AiScheduledTaskCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AiScheduledTaskCoordinator.class);
    /** 单次最多领取的任务数，避免外部模型短暂故障时造成并发放大。 */
    private static final int CLAIMS_PER_TICK = 2;
    private final AiScheduledTaskService taskService;
    private final ExecutorService ingestionExecutor;
    private final LlmWikiProperties properties;

    /** 创建 AI 定时任务协调器并复用应用级虚拟线程执行器。 */
    public AiScheduledTaskCoordinator(AiScheduledTaskService taskService, ExecutorService ingestionExecutor,
                                      LlmWikiProperties properties) {
        this.taskService = taskService;
        this.ingestionExecutor = ingestionExecutor;
        this.properties = properties;
    }

    /** 每分钟排队到期任务并以数据库租约领取待处理记录。 */
    @Scheduled(fixedDelay = 60000, initialDelay = 25000)
    public void poll() {
        try {
            taskService.enqueueDueTasks();
            for (int index = 0; index < CLAIMS_PER_TICK; index++) {
                AiScheduledTaskService.RunLease lease = taskService.claim(properties.worker().instanceId(),
                        properties.worker().leaseDuration().toSeconds());
                if (lease == null) return;
                log.info("AI scheduled task claimed taskId={} runId={} triggerType={}", lease.taskId(), lease.runId(), lease.triggerType());
                ingestionExecutor.submit(() -> taskService.process(lease, properties.worker().instanceId()));
            }
        } catch (Exception exception) {
            log.error("AI scheduled task scheduler tick failed", exception);
        }
    }
}
