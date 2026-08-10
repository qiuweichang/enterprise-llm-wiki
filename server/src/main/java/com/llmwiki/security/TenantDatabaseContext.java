package com.llmwiki.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * 在当前 PostgreSQL 事务中设置组织与空间上下文，供强制 RLS 策略执行数据库级租户隔离。
 */
@Component
public class TenantDatabaseContext {
    private final JdbcClient jdbc;

    /**
     * 创建租户数据库上下文组件。
     *
     * @param jdbc Spring JDBC 客户端
     */
    public TenantDatabaseContext(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 将当前请求的租户写入事务本地 PostgreSQL 设置。
     * 设置只在当前事务有效，归还连接后不会污染下一位用户。
     */
    public void applyCurrentRequest() {
        AuthenticatedUser user = RequestContext.require();
        apply(user.organizationId(), user.workspaceId());
    }

    /**
     * 为后台任务设置明确的租户上下文。
     *
     * @param organizationId 组织 ID
     * @param workspaceId 空间 ID
     */
    public void apply(UUID organizationId, UUID workspaceId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Tenant database context requires an active transaction");
        }
        // 本地开发按用户要求可用 postgres 连接，但事务立即降权到无登录、非超级用户角色，确保 FORCE RLS 真正生效。
        jdbc.sql("set local role llm_wiki_app").update();
        jdbc.sql("select set_config('app.current_organization_id', :organizationId, true), " +
                        "set_config('app.current_workspace_id', :workspaceId, true)")
                .param("organizationId", organizationId.toString())
                .param("workspaceId", workspaceId.toString())
                .query()
                .singleRow();
    }
}
