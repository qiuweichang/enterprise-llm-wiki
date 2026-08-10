package com.llmwiki.query;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

/**
 * 持续领取问 Wiki 消息并在虚拟线程执行，浏览器生命周期不会影响回答任务。
 */
@Component
public class QueryConversationCoordinator {
    private static final Logger log = LoggerFactory.getLogger(QueryConversationCoordinator.class);
    /** 每次轮询的并发上限，避免问答流量挤占摄取和维护任务。 */
    private static final int CLAIMS_PER_TICK = 4;
    /** 持久化会话与租约服务。 */
    private final QueryConversationService conversationService;
    /** 复用统一 Query 流程。 */
    private final QueryService queryService;
    /** 受应用生命周期管理的虚拟线程执行器。 */
    private final ExecutorService ingestionExecutor;

    /** 创建后台会话协调器。 */
    public QueryConversationCoordinator(QueryConversationService conversationService, QueryService queryService,
                                        ExecutorService ingestionExecutor) {
        this.conversationService = conversationService;
        this.queryService = queryService;
        this.ingestionExecutor = ingestionExecutor;
    }

    /** 每秒领取有限数量消息，多实例通过数据库租约自然分片。 */
    @Scheduled(fixedDelay = 1000, initialDelay = 5000)
    public void poll() {
        try {
            for (int index = 0; index < CLAIMS_PER_TICK; index++) {
                QueryConversationService.QueryLease lease = conversationService.claim();
                if (lease == null) {
                    return;
                }
                ingestionExecutor.submit(() -> process(lease));
            }
        } catch (Exception exception) {
            log.error("Query conversation scheduler tick failed", exception);
        }
    }

    /** 执行事务外检索和模型调用，再用短事务持久化结果。 */
    private void process(QueryConversationService.QueryLease lease) {
        try {
            QueryService.QueryResponse response = queryService.queryForUser(conversationService.workerUser(lease),
                    lease.question(), lease.retrievalText(), lease.conversationContext(), lease.useModel());
            conversationService.complete(lease, response);
        } catch (Exception exception) {
            try {
                conversationService.fail(lease, exception);
            } catch (Exception persistenceException) {
                log.error("Query conversation failure could not be persisted messageId={}",
                        lease.messageId(), persistenceException);
            }
        }
    }
}
