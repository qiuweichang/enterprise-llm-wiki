package com.llmwiki.wiki;

import com.llmwiki.security.AuthenticatedUser;
import com.llmwiki.security.RequestContext;
import com.llmwiki.security.TenantDatabaseContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 一次性加载 Wiki 图节点和边，替代旧前端逐页查询导致的 N+1 问题。
 */
@Service
public class GraphService {
    private final JdbcClient jdbc;
    private final TenantDatabaseContext tenantDatabaseContext;

    /** 创建图服务。 */
    public GraphService(JdbcClient jdbc, TenantDatabaseContext tenantDatabaseContext) {
        this.jdbc = jdbc;
        this.tenantDatabaseContext = tenantDatabaseContext;
    }

    /**
     * 返回当前空间最多 500 个节点及其关系边。
     */
    @Transactional(readOnly = true)
    public GraphSnapshot load() {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        List<Node> nodes = jdbc.sql("""
                        select id, slug, title, page_type, revision_no
                        from wiki_pages
                        where organization_id = :organizationId and workspace_id = :workspaceId and status = 'PUBLISHED'
                        order by updated_at desc limit 500
                        """).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .query((rs, rowNum) -> new Node((UUID) rs.getObject("id"), rs.getString("slug"), rs.getString("title"),
                        rs.getString("page_type"), rs.getInt("revision_no"))).list();
        List<Edge> edges = jdbc.sql("""
                        select r.id, r.from_page_id, r.to_page_id, r.relation_type
                        from wiki_relations r
                        join wiki_pages f on f.id = r.from_page_id and f.status = 'PUBLISHED'
                        join wiki_pages t on t.id = r.to_page_id and t.status = 'PUBLISHED'
                        where r.organization_id = :organizationId and r.workspace_id = :workspaceId
                        limit 2000
                        """).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .query((rs, rowNum) -> new Edge((UUID) rs.getObject("id"), (UUID) rs.getObject("from_page_id"),
                        (UUID) rs.getObject("to_page_id"), rs.getString("relation_type"))).list();
        return new GraphSnapshot(nodes, edges);
    }

    /** 图节点。 */
    public record Node(UUID id, String slug, String title, String pageType, int revisionNo) { }
    /** 图边。 */
    public record Edge(UUID id, UUID fromPageId, UUID toPageId, String relationType) { }
    /** 图快照。 */
    public record GraphSnapshot(List<Node> nodes, List<Edge> edges) { }
}

