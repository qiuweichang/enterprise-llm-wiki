package com.llmwiki.background;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

/**
 * 每分钟扫描到期空间并领取持续优化运行，实际 AI 分析在虚拟线程执行以免阻塞调度线程。
 */
@Component
public class EvolutionCoordinator {
    private static final Logger log = LoggerFactory.getLogger(EvolutionCoordinator.class);
    /** 每次调度限制领取数，避免模型服务抖动时瞬间放大并发。 */
    private static final int CLAIMS_PER_TICK = 2;
    /** 持久化运行、租约和审核提案的领域服务。 */
    private final EvolutionRunService runService;
    /** 受 Spring 生命周期管理的虚拟线程执行器，不由本协调器单独关闭。 */
    private final ExecutorService ingestionExecutor;

    /** 创建持续优化协调器并复用受应用生命周期管理的虚拟线程执行器。 */
    public EvolutionCoordinator(EvolutionRunService runService, ExecutorService ingestionExecutor) {
        this.runService = runService;
        this.ingestionExecutor = ingestionExecutor;
    }

    /** 扫描设置并并发提交有限数量运行，多实例依靠数据库租约自然分片。 */
    @Scheduled(fixedDelay = 60000, initialDelay = 20000)
    public void poll() {
        try {
            runService.enqueueDueRuns();
            for (int index = 0; index < CLAIMS_PER_TICK; index++) {
                EvolutionRunService.RunLease lease = runService.claim();
                if (lease == null) {
                    return;
                }
                log.info("Wiki evolution run claimed runId={} workspaceId={} triggerType={}",
                        lease.id(), lease.workspaceId(), lease.triggerType());
                ingestionExecutor.submit(() -> process(lease));
            }
        } catch (Exception exception) {
            log.error("Wiki evolution scheduler tick failed", exception);
        }
    }

    /**
     * 执行事务外分析并将结果交回短事务完成；任何异常都进入持久化失败记录。
     *
     * @param lease 已原子领取且带租约所有者的运行
     */
    private void process(EvolutionRunService.RunLease lease) {
        try {
            EvolutionRunService.EvolutionSnapshot snapshot = runService.loadSnapshot(lease);
            EvolutionRunService.EvolutionAnalysis analysis = runService.analyze(snapshot);
            runService.complete(snapshot, analysis);
        } catch (Exception exception) {
            runService.fail(lease, exception);
        }
    }
}
