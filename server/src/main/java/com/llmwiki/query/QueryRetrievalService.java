package com.llmwiki.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmwiki.security.AuthenticatedUser;
import com.llmwiki.security.TenantDatabaseContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 在 PostgreSQL RLS 租户上下文内融合关键词与语义排名、扩展图邻居，并记录查询运行。
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
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<RetrievedPage> retrieve(AuthenticatedUser user, String normalizedQuestion) {
        return retrieve(user, normalizedQuestion, List.of(), 0.5);
    }

    /** 在同一租户快照中融合关键词排名和当前版本的块级语义排名，再扩展图邻居；不调用网络。 */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<RetrievedPage> retrieve(AuthenticatedUser user, String normalizedQuestion, List<Double> vector, double minScore) {
        tenantDatabaseContext.apply(user.organizationId(), user.workspaceId());
        String pattern = "%" + normalizedQuestion.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
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
        List<RetrievedPage> semantic = vector.isEmpty() ? List.of() : jdbc.sql("""
                with scored as (
                  select c.page_id,c.ordinal,c.chunk_text,(select sum(a*b) from unnest(c.embedding,cast(:vector as real[])) as v(a,b)) as score
                  from semantic_chunks c join wiki_pages p on p.id=c.page_id
                  join semantic_jobs j on j.page_id=c.page_id
                  where p.status='PUBLISHED' and c.revision_id=p.current_revision_id
                    and j.status='DONE' and c.generation=j.generation and c.model_id=:model
                    and c.organization_id=:org and c.workspace_id=:ws
                ), ranked as (
                  select distinct on(page_id) page_id,chunk_text,score from scored order by page_id,score desc,ordinal
                )
                select p.id,p.slug,p.title,p.page_type,p.revision_no,
                       case when length(p.content_markdown)>5500 then r.chunk_text else p.content_markdown end as content_markdown,r.score
                from ranked r join wiki_pages p on p.id=r.page_id
                where r.score>=:threshold order by r.score desc,p.id limit 8
                """).param("vector",EmbeddingClient.array(vector)).param("model",EmbeddingClient.MODEL_ID)
                .param("org",user.organizationId()).param("ws",user.workspaceId()).param("threshold",minScore)
                .query(QueryRetrievalService::mapPage).list();
        primary = fuse(primary, semantic);
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

    /** RRF 按两路名次而非原始分值融合；同页多块先取最大相似度，避免长文档抢占召回席位。 */
    static List<RetrievedPage> fuse(List<RetrievedPage> lexical, List<RetrievedPage> semantic) {
        Map<UUID,Double> scores = new java.util.HashMap<>();
        Map<UUID,RetrievedPage> pages = new LinkedHashMap<>();
        for (var ranking : List.of(lexical, semantic)) for (int i=0;i<ranking.size();i++) {
            var page=ranking.get(i);
            // 第二路携带命中片段；长文档须将命中尾部传给回答器，而不是再次被全文头部截断。
            pages.put(page.id(),page);
            scores.merge(page.id(),1.0/(60+i+1),Double::sum);
        }
        return pages.values().stream().sorted(java.util.Comparator
                .comparingDouble((RetrievedPage p)->scores.get(p.id())).reversed().thenComparing(RetrievedPage::id))
                .limit(8).toList();
    }

    /**
     * 在独立短事务中记录查询答案和引用，缓存读写不持有数据库事务。
     */
    @Transactional
    public void record(AuthenticatedUser user, String question, String normalizedQuestion, String answer,
                       List<QueryService.Citation> citations, boolean cacheHit, long durationMs,
                       String answerMode, String modelProvider, String modelName,String retrievalMode,String retrievalMessage) {
        tenantDatabaseContext.apply(user.organizationId(), user.workspaceId());
        jdbc.sql("""
                        insert into query_runs(organization_id, workspace_id, user_id, question,
                                               normalized_question, answer_markdown, citations, cache_hit, duration_ms,
                                               answer_mode, model_provider, model_name,retrieval_mode,retrieval_message)
                        values (:organizationId, :workspaceId, :userId, :question, :normalizedQuestion,
                                :answer, cast(:citations as jsonb), :cacheHit, :durationMs,
                                :answerMode, :modelProvider, :modelName,:retrievalMode,:retrievalMessage)
                        """).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("userId", user.userId()).param("question", question).param("normalizedQuestion", normalizedQuestion)
                .param("answer", answer).param("citations", json(citations)).param("cacheHit", cacheHit)
                .param("durationMs", durationMs).param("answerMode", answerMode)
                .param("modelProvider", modelProvider).param("modelName", modelName)
                .param("retrievalMode",retrievalMode).param("retrievalMessage",retrievalMessage).update();
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
