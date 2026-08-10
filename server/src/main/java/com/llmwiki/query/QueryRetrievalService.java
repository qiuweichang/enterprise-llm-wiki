package com.llmwiki.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmwiki.security.AuthenticatedUser;
import com.llmwiki.security.TenantDatabaseContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 在 PostgreSQL RLS 租户上下文内执行混合全文、标题三元组和图邻居检索，并记录查询运行。
 */
@Service
public class QueryRetrievalService {
    private final JdbcClient jdbc;
    private final TenantDatabaseContext tenantDatabaseContext;
    private final ObjectMapper objectMapper;

    /** 创建查询检索服务。 */
    public QueryRetrievalService(JdbcClient jdbc, TenantDatabaseContext tenantDatabaseContext, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.tenantDatabaseContext = tenantDatabaseContext;
        this.objectMapper = objectMapper;
    }

    /**
     * 检索核心页面并扩展少量图邻居，避免旧实现中逐页 N+1 查询。
     *
     * @param user 当前身份
     * @param normalizedQuestion 规范化问题
     * @return 有序去重检索结果
     */
    @Transactional(readOnly = true)
    public List<RetrievedPage> retrieve(AuthenticatedUser user, String normalizedQuestion) {
        tenantDatabaseContext.apply(user.organizationId(), user.workspaceId());
        String pattern = "%" + normalizedQuestion.replace("%", "\\%").replace("_", "\\_") + "%";
        List<RetrievedPage> primary = jdbc.sql("""
                        with query as (select websearch_to_tsquery('simple', :question) as tsq)
                        select p.id, p.slug, p.title, p.page_type, p.revision_no, p.content_markdown,
                               (ts_rank_cd(p.search_vector, query.tsq) * 3
                                + similarity(lower(p.title), lower(:question)) * 2
                                + case when p.content_markdown ilike :pattern escape '\\' then 0.5 else 0 end) as score
                        from wiki_pages p cross join query
                        where p.organization_id = :organizationId and p.workspace_id = :workspaceId
                          and p.status = 'PUBLISHED'
                          and (p.search_vector @@ query.tsq or p.title % :question
                               or p.content_markdown ilike :pattern escape '\\')
                        order by score desc, p.updated_at desc
                        limit 8
                        """)
                .param("question", normalizedQuestion).param("pattern", pattern)
                .param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .query(QueryRetrievalService::mapPage).list();
        if (primary.isEmpty()) {
            primary = jdbc.sql("""
                            select id, slug, title, page_type, revision_no, content_markdown, 0.1 as score
                            from wiki_pages
                            where organization_id = :organizationId and workspace_id = :workspaceId
                              and status = 'PUBLISHED'
                            order by updated_at desc limit 5
                            """).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                    .query(QueryRetrievalService::mapPage).list();
        }
        LinkedHashMap<UUID, RetrievedPage> combined = new LinkedHashMap<>();
        primary.forEach(page -> combined.put(page.id(), page));
        if (!primary.isEmpty()) {
            List<UUID> ids = primary.stream().map(RetrievedPage::id).toList();
            List<RetrievedPage> neighbors = jdbc.sql("""
                            select distinct p.id, p.slug, p.title, p.page_type, p.revision_no,
                                   p.content_markdown, 0.05 as score
                            from wiki_relations r
                            join wiki_pages p on p.id = case when r.from_page_id in (:ids)
                                                            then r.to_page_id else r.from_page_id end
                            where (r.from_page_id in (:ids) or r.to_page_id in (:ids))
                              and p.status = 'PUBLISHED' and p.id not in (:ids)
                            limit 4
                            """).param("ids", ids).query(QueryRetrievalService::mapPage).list();
            neighbors.forEach(page -> combined.putIfAbsent(page.id(), page));
        }
        return new ArrayList<>(combined.values());
    }

    /**
     * 在独立短事务中记录查询答案和引用，缓存读写不持有数据库事务。
     */
    @Transactional
    public void record(AuthenticatedUser user, String question, String normalizedQuestion, String answer,
                       List<QueryService.Citation> citations, boolean cacheHit, long durationMs,
                       String answerMode, String modelProvider, String modelName) {
        tenantDatabaseContext.apply(user.organizationId(), user.workspaceId());
        jdbc.sql("""
                        insert into query_runs(organization_id, workspace_id, user_id, question,
                                               normalized_question, answer_markdown, citations, cache_hit, duration_ms,
                                               answer_mode, model_provider, model_name)
                        values (:organizationId, :workspaceId, :userId, :question, :normalizedQuestion,
                                :answer, cast(:citations as jsonb), :cacheHit, :durationMs,
                                :answerMode, :modelProvider, :modelName)
                        """).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("userId", user.userId()).param("question", question).param("normalizedQuestion", normalizedQuestion)
                .param("answer", answer).param("citations", json(citations)).param("cacheHit", cacheHit)
                .param("durationMs", durationMs).param("answerMode", answerMode)
                .param("modelProvider", modelProvider).param("modelName", modelName).update();
    }

    /** JSON 序列化查询引用。 */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize query citations", exception);
        }
    }

    /** 映射混合检索结果。 */
    private static RetrievedPage mapPage(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RetrievedPage((UUID) rs.getObject("id"), rs.getString("slug"), rs.getString("title"),
                rs.getString("page_type"), rs.getInt("revision_no"), rs.getString("content_markdown"),
                rs.getDouble("score"));
    }

    /** 供回答编排器使用的页面检索结果。 */
    public record RetrievedPage(UUID id, String slug, String title, String pageType, int revisionNo,
                                String contentMarkdown, double score) { }
}
