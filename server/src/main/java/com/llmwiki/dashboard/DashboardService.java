package com.llmwiki.dashboard;

import com.llmwiki.security.AuthenticatedUser;
import com.llmwiki.security.RequestContext;
import com.llmwiki.security.TenantDatabaseContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 聚合企业工作台指标、最近变更与审计活动，所有查询按当前租户过滤。
 */
@Service
public class DashboardService {
    private final JdbcClient jdbc;
    private final TenantDatabaseContext tenantDatabaseContext;

    /** 创建工作台服务。 */
    public DashboardService(JdbcClient jdbc, TenantDatabaseContext tenantDatabaseContext) {
        this.jdbc = jdbc;
        this.tenantDatabaseContext = tenantDatabaseContext;
    }

    /**
     * 以固定数量查询加载完整工作台，避免按卡片逐项请求造成前端瀑布。
     *
     * @return 工作台快照
     */
    @Transactional(readOnly = true)
    public DashboardSnapshot load() {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        Metrics metrics = jdbc.sql("""
                        select
                          (select count(*) from wiki_pages where organization_id = :organizationId and workspace_id = :workspaceId and status = 'PUBLISHED') as pages,
                          (select count(*) from change_sets where organization_id = :organizationId and workspace_id = :workspaceId and status = 'PENDING') as reviews,
                          (select count(*) from sources where organization_id = :organizationId and workspace_id = :workspaceId and status <> 'ARCHIVED') as sources,
                          (select count(*) from sources where organization_id = :organizationId and workspace_id = :workspaceId and status = 'READY') as ready_sources,
                          0 as reserved
                        """).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .query((rs, rowNum) -> new Metrics(rs.getLong("pages"), rs.getLong("reviews"), rs.getLong("sources"),
                        rs.getLong("ready_sources")))
                .single();
        List<ReviewItem> reviews = jdbc.sql("""
                        select cs.id, cs.title, cs.status, cs.risk, coalesce(s.title, '手工提案') as source_title, u.display_name, cs.created_at,
                               count(a.id) as action_count
                        from change_sets cs join users u on u.id = cs.proposed_by left join sources s on s.id = cs.source_id
                        left join change_set_actions a on a.change_set_id = cs.id
                        where cs.organization_id = :organizationId and cs.workspace_id = :workspaceId
                          and cs.status = 'PENDING'
                        group by cs.id, u.display_name, s.title
                        order by case cs.risk when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end, cs.created_at
                        limit 6
                        """).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .query(DashboardService::mapReview).list();
        List<RecentPage> pages = jdbc.sql("""
                        select id, slug, title, page_type, revision_no, updated_at,
                               exists(select 1 from evidence e where e.page_revision_id = wiki_pages.current_revision_id) as has_evidence
                        from wiki_pages
                        where organization_id = :organizationId and workspace_id = :workspaceId and status = 'PUBLISHED'
                        order by updated_at desc limit 6
                        """).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .query(DashboardService::mapPage).list();
        List<ActivityItem> activity = jdbc.sql("""
                        select a.id, a.action, a.resource_type, a.resource_id, a.created_at,
                               coalesce(u.display_name, '系统') as actor
                        from audit_logs a left join users u on u.id = a.actor_id
                        where a.organization_id = :organizationId and a.workspace_id = :workspaceId
                        order by a.created_at desc limit 8
                        """).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .query(DashboardService::mapActivity).list();
        List<SourceBreakdown> sourceBreakdown = jdbc.sql("""
                        select source_type, count(*) as total,
                               count(*) filter (where status = 'READY') as ready
                        from sources
                        where organization_id = :organizationId and workspace_id = :workspaceId and status <> 'ARCHIVED'
                        group by source_type order by source_type
                        """).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .query((rs, rowNum) -> new SourceBreakdown(rs.getString("source_type"), rs.getLong("total"),
                        rs.getLong("ready"))).list();
        return new DashboardSnapshot(metrics, reviews, pages, activity, sourceBreakdown);
    }

    /** 映射审核列表项。 */
    private static ReviewItem mapReview(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ReviewItem((UUID) rs.getObject("id"), rs.getString("title"), rs.getString("status"),
                rs.getString("source_title"), rs.getString("display_name"), rs.getInt("action_count"),
                rs.getTimestamp("created_at").toInstant());
    }

    /** 映射最近页面。 */
    private static RecentPage mapPage(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RecentPage((UUID) rs.getObject("id"), rs.getString("slug"), rs.getString("title"),
                rs.getString("page_type"), rs.getInt("revision_no"), rs.getBoolean("has_evidence"),
                rs.getTimestamp("updated_at").toInstant());
    }

    /** 映射审计活动。 */
    private static ActivityItem mapActivity(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ActivityItem((UUID) rs.getObject("id"), rs.getString("action"), rs.getString("resource_type"),
                (UUID) rs.getObject("resource_id"), rs.getString("actor"), rs.getTimestamp("created_at").toInstant());
    }

    /** 顶部指标。 */
    public record Metrics(long publishedPages, long pendingReviews, long activeSources, long readySources) { }
    /** 待审核工作台项。 */
    public record ReviewItem(UUID id, String title, String status, String sourceTitle, String proposedBy,
                             int actionCount, Instant createdAt) { }
    /** 最近更新页面。 */
    public record RecentPage(UUID id, String slug, String title, String pageType, int revisionNo,
                             boolean hasEvidence, Instant updatedAt) { }
    /** 最近活动。 */
    public record ActivityItem(UUID id, String action, String resourceType, UUID resourceId, String actor,
                               Instant createdAt) { }
    /** 来源处理拆分。 */
    public record SourceBreakdown(String sourceType, long total, long ready) { }
    /** 工作台聚合响应。 */
    public record DashboardSnapshot(Metrics metrics, List<ReviewItem> pendingReviews, List<RecentPage> recentPages,
                                    List<ActivityItem> recentActivity, List<SourceBreakdown> sourceBreakdown) { }
}
