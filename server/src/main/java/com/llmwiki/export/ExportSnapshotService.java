package com.llmwiki.export;

import com.llmwiki.security.AuthenticatedUser;
import com.llmwiki.security.RequestContext;
import com.llmwiki.security.TenantDatabaseContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 在只读事务内加载一致的 Wiki 导出快照，Zip 流式写出阶段不再占用数据库连接。
 */
@Service
public class ExportSnapshotService {
    private final JdbcClient jdbc;
    private final TenantDatabaseContext tenantDatabaseContext;

    /** 创建导出快照服务。 */
    public ExportSnapshotService(JdbcClient jdbc, TenantDatabaseContext tenantDatabaseContext) {
        this.jdbc = jdbc;
        this.tenantDatabaseContext = tenantDatabaseContext;
    }

    /**
     * 加载当前空间全部已发布页面、来源标题和近期审计。
     *
     * @return 可在事务外安全使用的不可变快照
     */
    @Transactional(readOnly = true)
    public ExportSnapshot load() {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        String workspaceName = jdbc.sql("select name from workspaces where id = :id and organization_id = :organizationId")
                .param("id", user.workspaceId()).param("organizationId", user.organizationId())
                .query(String.class).single();
        List<ExportPage> pages = jdbc.sql("""
                        select p.id, p.slug, p.title, p.page_type, p.revision_no, p.content_markdown, p.updated_at,
                               coalesce(array_agg(distinct s.title order by s.title)
                                        filter (where s.title is not null), '{}'::text[]) as sources
                        from wiki_pages p
                        left join evidence e on e.page_revision_id = p.current_revision_id
                        left join sources s on s.id = e.source_id
                        where p.organization_id = :organizationId and p.workspace_id = :workspaceId
                          and p.status = 'PUBLISHED'
                        group by p.id order by p.page_type, p.title
                        """).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .query(ExportSnapshotService::mapPage).list();
        List<AuditLine> audit = jdbc.sql("""
                        select a.action, a.resource_type, a.resource_id, coalesce(u.display_name, 'System') as actor,
                               a.created_at
                        from audit_logs a left join users u on u.id = a.actor_id
                        where a.organization_id = :organizationId and a.workspace_id = :workspaceId
                        order by a.created_at desc limit 200
                        """).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .query((rs, rowNum) -> new AuditLine(rs.getString("action"), rs.getString("resource_type"),
                        (UUID) rs.getObject("resource_id"), rs.getString("actor"),
                        rs.getTimestamp("created_at").toInstant())).list();
        return new ExportSnapshot(workspaceName, Instant.now(), pages, audit);
    }

    /** 映射导出页面和来源数组。 */
    private static ExportPage mapPage(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String[] sources = (String[]) rs.getArray("sources").getArray();
        return new ExportPage((UUID) rs.getObject("id"), rs.getString("slug"), rs.getString("title"),
                rs.getString("page_type"), rs.getInt("revision_no"), rs.getString("content_markdown"),
                rs.getTimestamp("updated_at").toInstant(), Arrays.asList(sources));
    }

    /** 导出页面。 */
    public record ExportPage(UUID id, String slug, String title, String pageType, int revisionNo,
                             String contentMarkdown, Instant updatedAt, List<String> sources) { }
    /** 审计日志行。 */
    public record AuditLine(String action, String resourceType, UUID resourceId, String actor, Instant createdAt) { }
    /** 一致导出快照。 */
    public record ExportSnapshot(String workspaceName, Instant exportedAt, List<ExportPage> pages,
                                 List<AuditLine> audit) { }
}

