package com.llmwiki.background;

import com.llmwiki.audit.AuditService;
import com.llmwiki.security.AuthenticatedUser;
import com.llmwiki.security.RequestContext;
import com.llmwiki.security.TenantDatabaseContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 管理工作空间自动刷新和维护策略；策略本身按显式组织/空间键隔离。
 */
@Service
public class AutomationSettingsService {
    /** 自动化策略与待取消定时运行的事务化数据库入口。 */
    private final JdbcClient jdbc;
    /** 策略变更的同事务审计入口。 */
    private final AuditService auditService;
    /** 强制所有设置读写限定在当前组织和空间。 */
    private final TenantDatabaseContext tenantDatabaseContext;
    /** 开启持续优化前校验模型已经启用且凭据可解析。 */
    private final ModelSettingsService modelSettingsService;

    /**
     * 创建自动化设置服务。
     *
     * @param jdbc 数据库客户端
     * @param auditService 同事务审计服务
     * @param tenantDatabaseContext PostgreSQL 强制租户上下文
     * @param modelSettingsService 模型运行配置校验服务
     */
    public AutomationSettingsService(JdbcClient jdbc, AuditService auditService,
                                     TenantDatabaseContext tenantDatabaseContext,
                                     ModelSettingsService modelSettingsService) {
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.tenantDatabaseContext = tenantDatabaseContext;
        this.modelSettingsService = modelSettingsService;
    }

    /**
     * 读取当前空间自动化设置，不存在时按安全默认值补建。
     *
     * @return 自动化设置
     */
    @Transactional
    public Settings get() {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        jdbc.sql("""
                        insert into workspace_automation_settings(organization_id, workspace_id, updated_by)
                        values (:organizationId, :workspaceId, :userId)
                        on conflict (workspace_id) do nothing
                        """).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("userId", user.userId()).update();
        return find(user);
    }

    /**
     * 更新自动化策略。
     *
     * @param request 新设置
     * @return 已保存设置
     */
    @Transactional
    public Settings update(Settings request) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        int hours = Math.max(1, Math.min(8760, request.refreshIntervalHours()));
        int optimizationMinutes = Math.max(30, Math.min(10080, request.optimizationIntervalMinutes()));
        if (request.maintenanceEnabled()) {
            modelSettingsService.requireRunnable(user.organizationId(), user.workspaceId());
        }
        jdbc.sql("""
                        insert into workspace_automation_settings(organization_id, workspace_id,
                            automatic_url_refresh, refresh_interval_hours, maintenance_enabled,
                            optimization_interval_minutes, next_optimization_at, updated_by)
                        values (:organizationId, :workspaceId, :refresh, :hours, :maintenance,
                                :optimizationMinutes, now(), :userId)
                        on conflict (workspace_id) do update set
                            automatic_url_refresh = excluded.automatic_url_refresh,
                            refresh_interval_hours = excluded.refresh_interval_hours,
                            maintenance_enabled = excluded.maintenance_enabled,
                            optimization_interval_minutes = excluded.optimization_interval_minutes,
                            next_optimization_at = case
                                when excluded.maintenance_enabled and not workspace_automation_settings.maintenance_enabled
                                    then now()
                                when excluded.maintenance_enabled and
                                     excluded.optimization_interval_minutes <> workspace_automation_settings.optimization_interval_minutes
                                    then now() + cast(excluded.optimization_interval_minutes || ' minutes' as interval)
                                else workspace_automation_settings.next_optimization_at
                            end,
                            updated_by = excluded.updated_by, updated_at = now()
                        where workspace_automation_settings.organization_id = :organizationId
                        """).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("refresh", request.automaticUrlRefresh()).param("hours", hours)
                .param("maintenance", request.maintenanceEnabled()).param("optimizationMinutes", optimizationMinutes)
                .param("userId", user.userId()).update();
        if (!request.maintenanceEnabled()) {
            jdbc.sql("""
                            update wiki_evolution_runs set status = 'SKIPPED',
                                result_summary = '持续优化已由管理员关闭，排队任务不再执行。', finished_at = now()
                            where organization_id = :organizationId and workspace_id = :workspaceId
                              and trigger_type = 'SCHEDULED' and status = 'PENDING'
                            """).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                    .update();
        }
        auditService.record("AUTOMATION_SETTINGS_UPDATED", "WORKSPACE", user.workspaceId(), null,
                java.util.Map.of("automaticUrlRefresh", request.automaticUrlRefresh(),
                        "refreshIntervalHours", hours, "maintenanceEnabled", request.maintenanceEnabled(),
                        "optimizationIntervalMinutes", optimizationMinutes), java.util.Map.of());
        return find(user);
    }

    /** 按租户键读取设置。 */
    private Settings find(AuthenticatedUser user) {
        return jdbc.sql("""
                        select automatic_url_refresh, refresh_interval_hours, maintenance_enabled,
                               optimization_interval_minutes, next_optimization_at, last_optimization_at
                        from workspace_automation_settings
                        where organization_id = :organizationId and workspace_id = :workspaceId
                        """).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .query((rs, rowNum) -> new Settings(rs.getBoolean("automatic_url_refresh"),
                        rs.getInt("refresh_interval_hours"), rs.getBoolean("maintenance_enabled"),
                        rs.getInt("optimization_interval_minutes"), rs.getTimestamp("next_optimization_at").toInstant(),
                        rs.getTimestamp("last_optimization_at") == null ? null : rs.getTimestamp("last_optimization_at").toInstant())).single();
    }

    /** 自动化设置。 */
    public record Settings(boolean automaticUrlRefresh, int refreshIntervalHours, boolean maintenanceEnabled,
                           int optimizationIntervalMinutes, Instant nextOptimizationAt,
                           Instant lastOptimizationAt) { }
}
