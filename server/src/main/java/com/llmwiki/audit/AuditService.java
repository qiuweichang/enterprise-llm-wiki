package com.llmwiki.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmwiki.security.AuthenticatedUser;
import com.llmwiki.security.RequestContext;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * 在业务事务内同步写入审计记录和 Outbox 事件，保证状态变化与可追溯事件原子提交。
 */
@Service
public class AuditService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    /**
     * 创建审计服务。
     *
     * @param jdbc 数据库客户端
     * @param objectMapper JSON 序列化器
     */
    public AuditService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 为当前请求记录审计事件。
     *
     * @param action 动作代码
     * @param resourceType 资源类型
     * @param resourceId 资源 ID
     * @param beforeState 变更前快照，可为空
     * @param afterState 变更后快照，可为空
     * @param metadata 附加上下文
     */
    public void record(String action, String resourceType, UUID resourceId,
                       Object beforeState, Object afterState, Map<String, ?> metadata) {
        AuthenticatedUser user = RequestContext.require();
        record(user.organizationId(), user.workspaceId(), user.userId(), action, resourceType, resourceId,
                beforeState, afterState, metadata);
    }

    /**
     * 为后台任务记录显式租户审计事件。
     *
     * @param organizationId 组织 ID
     * @param workspaceId 空间 ID
     * @param actorId 发起用户或系统代行用户
     * @param action 动作代码
     * @param resourceType 资源类型
     * @param resourceId 资源 ID
     * @param beforeState 变更前快照
     * @param afterState 变更后快照
     * @param metadata 附加上下文
     */
    public void record(UUID organizationId, UUID workspaceId, UUID actorId, String action, String resourceType,
                       UUID resourceId, Object beforeState, Object afterState, Map<String, ?> metadata) {
        jdbc.sql("""
                        insert into audit_logs(organization_id, workspace_id, actor_id, action, resource_type,
                                               resource_id, request_id, before_state, after_state, metadata)
                        values (:organizationId, :workspaceId, :actorId, :action, :resourceType, :resourceId,
                                :requestId, cast(:beforeState as jsonb), cast(:afterState as jsonb), cast(:metadata as jsonb))
                        """)
                .param("organizationId", organizationId)
                .param("workspaceId", workspaceId)
                .param("actorId", actorId)
                .param("action", action)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .param("requestId", MDC.get("requestId"))
                .param("beforeState", json(beforeState))
                .param("afterState", json(afterState))
                .param("metadata", json(metadata == null ? Map.of() : metadata))
                .update();
    }

    /**
     * 将领域事件写入事务 Outbox，异步发布失败不会回滚已完成的业务事务。
     *
     * @param aggregateType 聚合类型
     * @param aggregateId 聚合 ID
     * @param eventType 事件类型
     * @param payload 事件载荷
     */
    public void outbox(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        AuthenticatedUser user = RequestContext.require();
        outbox(user.organizationId(), user.workspaceId(), aggregateType, aggregateId, eventType, payload);
    }

    /**
     * 为后台任务写入显式租户 Outbox 事件。
     */
    public void outbox(UUID organizationId, UUID workspaceId, String aggregateType, UUID aggregateId,
                       String eventType, Object payload) {
        jdbc.sql("""
                        insert into outbox_events(organization_id, workspace_id, aggregate_type, aggregate_id,
                                                  event_type, payload)
                        values (:organizationId, :workspaceId, :aggregateType, :aggregateId, :eventType,
                                cast(:payload as jsonb))
                        """)
                .param("organizationId", organizationId)
                .param("workspaceId", workspaceId)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", json(payload))
                .update();
    }

    /** 将任意对象安全序列化为 PostgreSQL JSONB 文本。 */
    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize audit payload", exception);
        }
    }
}

