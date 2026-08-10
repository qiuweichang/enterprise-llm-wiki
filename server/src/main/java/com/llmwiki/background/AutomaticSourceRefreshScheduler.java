package com.llmwiki.background;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmwiki.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 按空间策略自动重新抓取过期 URL 来源。
 * 这里只创建任务，不在事务中联网；重新编译若命中已有页面，仍必然进入人工审核。
 */
@Component
public class AutomaticSourceRefreshScheduler {
    private static final Logger log = LoggerFactory.getLogger(AutomaticSourceRefreshScheduler.class);
    private final JdbcClient jdbc;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** 创建自动刷新调度器。 */
    public AutomaticSourceRefreshScheduler(JdbcClient jdbc, AuditService auditService, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * 每分钟扫描最多十个到期 URL，多实例通过来源行锁跳过彼此已选记录。
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    @Transactional
    public void enqueueDueSources() {
        List<DueSource> due = jdbc.sql("""
                        select s.id, s.organization_id, s.workspace_id, s.created_by, s.canonical_uri
                        from sources s
                        join workspace_automation_settings a on a.workspace_id = s.workspace_id
                            and a.organization_id = s.organization_id
                        where a.automatic_url_refresh and s.source_type = 'URL' and s.status = 'READY'
                          and s.updated_at < now() - (a.refresh_interval_hours::text || ' hours')::interval
                          and not exists(select 1 from ingestion_jobs j where j.source_id = s.id
                              and j.status in ('PENDING', 'PROCESSING'))
                        order by s.updated_at limit 10 for update of s skip locked
                        """).query((rs, rowNum) -> new DueSource((UUID) rs.getObject("id"),
                        (UUID) rs.getObject("organization_id"), (UUID) rs.getObject("workspace_id"),
                        (UUID) rs.getObject("created_by"), rs.getString("canonical_uri"))).list();
        for (DueSource source : due) {
            UUID jobId = UUID.randomUUID();
            jdbc.sql("""
                            insert into ingestion_jobs(id, organization_id, workspace_id, source_id, requested_by, payload)
                            values (:id, :organizationId, :workspaceId, :sourceId, :userId, cast(:payload as jsonb))
                            """).param("id", jobId).param("organizationId", source.organizationId())
                    .param("workspaceId", source.workspaceId()).param("sourceId", source.id())
                    .param("userId", source.createdBy()).param("payload", json(Map.of("url", source.url(), "automatic", true)))
                    .update();
            jdbc.sql("update sources set status = 'PENDING', updated_at = now() where id = :id")
                    .param("id", source.id()).update();
            auditService.record(source.organizationId(), source.workspaceId(), source.createdBy(),
                    "SOURCE_AUTO_REFRESH_ENQUEUED", "SOURCE", source.id(), null,
                    Map.of("jobId", jobId), Map.of("automatic", true));
            log.info("Automatic URL refresh enqueued sourceId={} jobId={} workspaceId={}",
                    source.id(), jobId, source.workspaceId());
        }
    }

    /** 序列化任务载荷。 */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize automatic refresh payload", exception);
        }
    }

    /** 到期来源。 */
    private record DueSource(UUID id, UUID organizationId, UUID workspaceId, UUID createdBy, String url) { }
}
