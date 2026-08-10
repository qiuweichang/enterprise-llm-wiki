package com.llmwiki.background;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 事务 Outbox 的本地发布器。当前发布目标是结构化 info 日志；未来接入消息中间件时可保留相同表和领取语义。
 */
@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcClient jdbc;

    /** 创建 Outbox 发布器。 */
    public OutboxPublisher(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 锁定并发布一小批事件。日志和状态更新位于同一短事务，多个实例用 SKIP LOCKED 分片。
     */
    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void publishBatch() {
        List<OutboxEvent> events = jdbc.sql("""
                        select id, aggregate_type, aggregate_id, event_type, payload::text
                        from outbox_events
                        where status = 'PENDING' and available_at <= now()
                        order by created_at limit 50 for update skip locked
                        """).query((rs, rowNum) -> new OutboxEvent((UUID) rs.getObject("id"),
                        rs.getString("aggregate_type"), (UUID) rs.getObject("aggregate_id"),
                        rs.getString("event_type"), rs.getString("payload"))).list();
        for (OutboxEvent event : events) {
            log.info("Domain event published eventId={} eventType={} aggregateType={} aggregateId={} payload={}",
                    event.id(), event.eventType(), event.aggregateType(), event.aggregateId(), event.payload());
            jdbc.sql("""
                            update outbox_events set status = 'PUBLISHED', attempts = attempts + 1, published_at = now()
                            where id = :id and status = 'PENDING'
                            """).param("id", event.id()).update();
        }
    }

    /** 待发布领域事件。 */
    private record OutboxEvent(UUID id, String aggregateType, UUID aggregateId, String eventType, String payload) { }
}
