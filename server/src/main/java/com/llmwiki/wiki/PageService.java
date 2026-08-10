package com.llmwiki.wiki;

import com.llmwiki.audit.AuditService;
import com.llmwiki.cache.WikiCacheService;
import com.llmwiki.common.ApiException;
import com.llmwiki.common.ContentHash;
import com.llmwiki.common.Slugifier;
import com.llmwiki.security.AuthenticatedUser;
import com.llmwiki.security.RequestContext;
import com.llmwiki.security.TenantDatabaseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 维护已发布 Wiki 页面和手工变更提案。
 * 新页面仅在具备 PAGE_CREATE_PUBLISH 时可直接发布，既有页面更新无条件进入审核。
 */
@Service
public class PageService {
    private static final Logger log = LoggerFactory.getLogger(PageService.class);
    private final JdbcClient jdbc;
    private final TenantDatabaseContext tenantDatabaseContext;
    private final AuditService auditService;
    private final WikiCacheService cacheService;

    /** 创建页面服务。 */
    public PageService(JdbcClient jdbc, TenantDatabaseContext tenantDatabaseContext,
                       AuditService auditService, WikiCacheService cacheService) {
        this.jdbc = jdbc;
        this.tenantDatabaseContext = tenantDatabaseContext;
        this.auditService = auditService;
        this.cacheService = cacheService;
    }

    /**
     * 按更新时间游标列出已发布页面。
     *
     * @param cursorUpdatedAt 上一页最后更新时间，可为空
     * @param cursorId 上一页最后 ID，可为空
     * @param limit 返回数量，服务端限制为 100
     * @return 页面概要列表
     */
    @Transactional(readOnly = true)
    public List<PageSummary> list(Instant cursorUpdatedAt, UUID cursorId, int limit) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String cursorClause = cursorUpdatedAt == null || cursorId == null ? "" :
                " and (updated_at, id) < (:cursorUpdatedAt, :cursorId)";
        JdbcClient.StatementSpec statement = jdbc.sql("""
                        select id, slug, title, page_type, revision_no, updated_at,
                               left(regexp_replace(content_markdown, '\\s+', ' ', 'g'), 180) as excerpt
                        from wiki_pages
                        where organization_id = :organizationId and workspace_id = :workspaceId
                          and status = 'PUBLISHED'
                        """ + cursorClause + " order by updated_at desc, id desc limit :limit")
                .param("organizationId", user.organizationId())
                .param("workspaceId", user.workspaceId())
                .param("limit", safeLimit);
        if (!cursorClause.isEmpty()) {
            statement = statement.param("cursorUpdatedAt", java.time.OffsetDateTime.ofInstant(cursorUpdatedAt,
                    java.time.ZoneOffset.UTC)).param("cursorId", cursorId);
        }
        return statement.query(PageService::mapSummary).list();
    }

    /**
     * 读取页面正文、证据和反向链接。
     *
     * @param pageId 页面 ID
     * @return 页面详情
     */
    @Transactional(readOnly = true)
    public PageDetail get(UUID pageId) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        PageRow page = jdbc.sql("""
                        select id, slug, title, page_type, revision_no, current_revision_id,
                               content_markdown, created_at, updated_at
                        from wiki_pages
                        where id = :pageId and organization_id = :organizationId and workspace_id = :workspaceId
                          and status = 'PUBLISHED'
                        """)
                .param("pageId", pageId).param("organizationId", user.organizationId())
                .param("workspaceId", user.workspaceId())
                .query(PageService::mapPage)
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAGE_NOT_FOUND", "知识页面不存在"));
        List<EvidenceItem> evidence = jdbc.sql("""
                        select e.id, e.quote_text, e.locator, s.id as source_id, s.title as source_title,
                               s.source_type, s.canonical_uri
                        from evidence e join sources s on s.id = e.source_id
                        where e.page_revision_id = :revisionId
                        order by e.created_at
                        """)
                .param("revisionId", page.currentRevisionId())
                .query(PageService::mapEvidence).list();
        List<Backlink> backlinks = jdbc.sql("""
                        select p.id, p.slug, p.title, r.relation_type
                        from wiki_relations r join wiki_pages p on p.id = r.from_page_id
                        where r.to_page_id = :pageId and p.status = 'PUBLISHED'
                        order by p.title
                        """)
                .param("pageId", pageId).query(PageService::mapBacklink).list();
        List<RelatedPage> relatedPages = jdbc.sql("""
                        select p.id, p.slug, p.title, p.page_type, r.relation_type
                        from wiki_relations r join wiki_pages p on p.id = r.to_page_id
                        where r.from_page_id = :pageId and p.status = 'PUBLISHED'
                        order by p.title
                        """).param("pageId", pageId).query(PageService::mapRelatedPage).list();
        List<PageHistoryItem> history = jdbc.sql("""
                        select cs.id, cs.title, cs.summary, cs.status, cs.change_type,
                               cs.created_at, cs.resolved_at, proposer.display_name as proposed_by,
                               review.reviewer_name, review.decision, review.comment
                        from change_sets cs
                        join users proposer on proposer.id = cs.proposed_by
                        left join lateral (
                            select reviewer.display_name as reviewer_name, r.decision, r.comment
                            from reviews r join users reviewer on reviewer.id = r.reviewer_id
                            where r.change_set_id = cs.id
                            order by r.created_at desc limit 1
                        ) review on true
                        where cs.organization_id = :organizationId and cs.workspace_id = :workspaceId
                          and exists (select 1 from change_set_actions a
                                      where a.change_set_id = cs.id and a.page_id = :pageId)
                        order by cs.created_at desc limit 5
                        """).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("pageId", pageId).query(PageService::mapHistory).list();
        return new PageDetail(page.id(), page.slug(), page.title(), page.pageType(), page.revisionNo(),
                page.currentRevisionId(), page.content(), page.createdAt(), page.updatedAt(), evidence, backlinks, relatedPages, history);
    }

    /** 为删除主题或实体创建归档审核提案，审核通过后保留版本与审计历史。 */
    @Transactional
    public MutationResult proposeArchive(UUID pageId) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        ExistingPage page = jdbc.sql("""
                        select id, title, slug, page_type, current_revision_id, content_markdown, content_hash
                        from wiki_pages where id = :pageId and organization_id = :organizationId
                          and workspace_id = :workspaceId and status = 'PUBLISHED' for update
                        """).param("pageId", pageId).param("organizationId", user.organizationId())
                .param("workspaceId", user.workspaceId()).query((rs, rowNum) -> new ExistingPage(
                        (UUID) rs.getObject("id"), rs.getString("title"), rs.getString("slug"),
                        rs.getString("page_type"), (UUID) rs.getObject("current_revision_id"),
                        rs.getString("content_markdown"), rs.getString("content_hash"))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAGE_NOT_FOUND", "知识页面不存在"));
        if (!user.hasPermission("PAGE_UPDATE_PROPOSE")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "当前角色不能删除知识");
        }
        UUID changeSetId = insertChangeSet("MANUAL", page.title(), "删除知识页面，必须审核后归档", "HIGH", null, user);
        insertAction(changeSetId, "ARCHIVE_PAGE", page.id(), page.currentRevisionId(), page.slug(), page.title(),
                page.pageType(), page.content(), 0, user);
        auditService.record("PAGE_ARCHIVE_PROPOSED", "WIKI_PAGE", page.id(), Map.of("status", "PUBLISHED"),
                Map.of("status", "PENDING_REVIEW", "changeSetId", changeSetId), Map.of());
        return new MutationResult("PENDING_REVIEW", page.id(), changeSetId, "删除知识已提交审核");
    }

    /** 将一个页面正文中的 WikiLink 移除并生成审核提案，避免用户手工编辑整页内容。 */
    @Transactional
    public MutationResult proposeRemoveRelation(UUID pageId, UUID relatedPageId) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        PageRow page = jdbc.sql("""
                        select id, slug, title, page_type, revision_no, current_revision_id,
                               content_markdown, created_at, updated_at
                        from wiki_pages where id = :pageId and organization_id = :organizationId
                          and workspace_id = :workspaceId and status = 'PUBLISHED' for update
                        """).param("pageId", pageId).param("organizationId", user.organizationId())
                .param("workspaceId", user.workspaceId()).query(PageService::mapPage).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAGE_NOT_FOUND", "知识页面不存在"));
        String relatedTitle = jdbc.sql("select title from wiki_pages where id = :id and organization_id = :organizationId and workspace_id = :workspaceId and status = 'PUBLISHED'")
                .param("id", relatedPageId).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .query(String.class).optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RELATED_PAGE_NOT_FOUND", "关联知识不存在"));
        boolean relationExists = jdbc.sql("select exists(select 1 from wiki_relations where from_page_id = :pageId and to_page_id = :relatedPageId)")
                .param("pageId", pageId).param("relatedPageId", relatedPageId).query(Boolean.class).single();
        if (!relationExists) throw new ApiException(HttpStatus.CONFLICT, "RELATION_NOT_FOUND", "关联知识已经不存在");
        String updatedContent = page.content().replaceAll("(?m)^\\s*[-*]?\\s*\\[\\[" + java.util.regex.Pattern.quote(relatedTitle) + "\\]\\]\\s*$\\R?", "")
                .replace("[[" + relatedTitle + "]]", "").replaceAll("\\n{3,}", "\\n\\n").trim();
        if (updatedContent.equals(page.content().trim())) throw new ApiException(HttpStatus.CONFLICT, "RELATION_NOT_EDITABLE", "未在正文中找到可删除的关联链接");
        return proposeUpdate(new ExistingPage(page.id(), page.title(), page.slug(), page.pageType(), page.currentRevisionId(), page.content(), ContentHash.sha256(page.content())),
                page.title(), page.slug(), page.pageType(), updatedContent, user);
    }

    /**
     * 创建新页面或为既有页面提交更新提案。
     *
     * @param request 页面内容
     * @return 直接发布或待审核的结果
     */
    @Transactional
    public MutationResult createOrPropose(PageMutationRequest request) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        String title = requireText(request.title(), "标题不能为空");
        String content = requireText(request.contentMarkdown(), "页面正文不能为空");
        String slug = Slugifier.slugify(request.slug() == null || request.slug().isBlank() ? title : request.slug());
        ExistingPage existing = request.pageId() == null ? findBySlug(slug) : findById(request.pageId());
        if (existing != null) {
            return proposeUpdate(existing, title, slug, normalizePageType(request.pageType()), content, user);
        }
        if (user.hasPermission("PAGE_CREATE_PUBLISH")) {
            return publishNew(title, slug, normalizePageType(request.pageType()), content, user);
        }
        return proposeCreate(title, slug, normalizePageType(request.pageType()), content, user);
    }

    /** 发布具备授权的新页面并写入首个不可变修订。 */
    private MutationResult publishNew(String title, String slug, String pageType, String content, AuthenticatedUser user) {
        UUID pageId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        String hash = ContentHash.sha256(content);
        jdbc.sql("""
                        insert into wiki_pages(id, organization_id, workspace_id, slug, title, page_type,
                                               revision_no, content_markdown, content_hash, created_by, updated_by)
                        values (:id, :organizationId, :workspaceId, :slug, :title, :pageType,
                                1, :content, :hash, :userId, :userId)
                        """)
                .param("id", pageId).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("slug", slug).param("title", title).param("pageType", pageType).param("content", content)
                .param("hash", hash).param("userId", user.userId()).update();
        jdbc.sql("""
                        insert into wiki_page_revisions(id, organization_id, workspace_id, page_id, revision_no,
                                                        title, content_markdown, content_hash, change_summary, published_by)
                        values (:id, :organizationId, :workspaceId, :pageId, 1, :title, :content, :hash,
                                'Authorized new page publication', :userId)
                        """)
                .param("id", revisionId).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("pageId", pageId).param("title", title).param("content", content).param("hash", hash)
                .param("userId", user.userId()).update();
        jdbc.sql("update wiki_pages set current_revision_id = :revisionId where id = :pageId")
                .param("revisionId", revisionId).param("pageId", pageId).update();
        auditService.record("PAGE_CREATED", "WIKI_PAGE", pageId, null,
                Map.of("title", title, "revisionId", revisionId), Map.of("mode", "DIRECT_PUBLISH"));
        auditService.outbox("WIKI_PAGE", pageId, "wiki.page.published", Map.of("revisionId", revisionId));
        cacheService.advanceWorkspaceEpoch(user.organizationId(), user.workspaceId());
        log.info("New wiki page published pageId={} workspaceId={} actorId={}", pageId, user.workspaceId(), user.userId());
        return new MutationResult("PUBLISHED", pageId, null, "新页面已发布");
    }

    /** 为无直接发布权限的新增页面创建待审核变更集。 */
    private MutationResult proposeCreate(String title, String slug, String pageType, String content, AuthenticatedUser user) {
        UUID changeSetId = insertChangeSet("MANUAL", title, "新增页面提案", "LOW", null, user);
        insertAction(changeSetId, "CREATE_PAGE", null, null, slug, title, pageType, content, 0, user);
        auditService.record("CHANGE_SET_SUBMITTED", "CHANGE_SET", changeSetId, null,
                Map.of("action", "CREATE_PAGE", "title", title), Map.of());
        auditService.outbox("CHANGE_SET", changeSetId, "wiki.change-set.submitted", Map.of("action", "CREATE_PAGE"));
        log.info("New page proposal submitted changeSetId={} workspaceId={} actorId={}", changeSetId,
                user.workspaceId(), user.userId());
        return new MutationResult("PENDING_REVIEW", null, changeSetId, "新增页面已提交审核");
    }

    /** 为任何既有页面更新创建待审核变更集，权限不能绕过此规则。 */
    private MutationResult proposeUpdate(ExistingPage existing, String title, String slug, String pageType,
                                         String content, AuthenticatedUser user) {
        if (!user.hasPermission("PAGE_UPDATE_PROPOSE")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "当前角色不能修改已有知识");
        }
        if (ContentHash.sha256(content).equals(existing.contentHash()) && title.equals(existing.title())) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_CONTENT_CHANGE", "提交内容与当前版本相同");
        }
        UUID changeSetId = insertChangeSet("MANUAL", title, "既有知识更新，必须审核后发布", "MEDIUM",
                null, user);
        insertAction(changeSetId, "UPDATE_PAGE", existing.id(), existing.currentRevisionId(), slug, title,
                pageType, content, 0, user);
        auditService.record("CHANGE_SET_SUBMITTED", "CHANGE_SET", changeSetId, null,
                Map.of("action", "UPDATE_PAGE", "pageId", existing.id(), "baseRevisionId", existing.currentRevisionId()),
                Map.of("reviewRequired", true));
        auditService.outbox("CHANGE_SET", changeSetId, "wiki.change-set.submitted",
                Map.of("action", "UPDATE_PAGE", "pageId", existing.id()));
        log.info("Existing page update submitted for review pageId={} changeSetId={} actorId={}",
                existing.id(), changeSetId, user.userId());
        return new MutationResult("PENDING_REVIEW", existing.id(), changeSetId, "已有知识更新已提交审核");
    }

    /** 插入变更集主记录。 */
    private UUID insertChangeSet(String type, String title, String summary, String risk,
                                 UUID sourceId, AuthenticatedUser user) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into change_sets(id, organization_id, workspace_id, change_type, status, title,
                                                summary, risk, source_id, proposed_by, submitted_at)
                        values (:id, :organizationId, :workspaceId, :type, 'PENDING', :title,
                                :summary, :risk, :sourceId, :userId, now())
                        """)
                .param("id", id).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("type", type).param("title", title).param("summary", summary).param("risk", risk)
                .param("sourceId", sourceId).param("userId", user.userId()).update();
        return id;
    }

    /** 插入一条候选页面动作。 */
    private void insertAction(UUID changeSetId, String actionType, UUID pageId, UUID baseRevisionId,
                              String slug, String title, String pageType, String content, int ordinal,
                              AuthenticatedUser user) {
        jdbc.sql("""
                        insert into change_set_actions(organization_id, workspace_id, change_set_id, action_type,
                                                       page_id, base_revision_id, proposed_slug, proposed_title,
                                                       proposed_page_type, proposed_content_markdown, content_hash, ordinal)
                        values (:organizationId, :workspaceId, :changeSetId, :actionType, :pageId, :baseRevisionId,
                                :slug, :title, :pageType, :content, :hash, :ordinal)
                        """)
                .param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("changeSetId", changeSetId).param("actionType", actionType).param("pageId", pageId)
                .param("baseRevisionId", baseRevisionId).param("slug", slug).param("title", title)
                .param("pageType", pageType).param("content", content).param("hash", ContentHash.sha256(content))
                .param("ordinal", ordinal).update();
    }

    /** 按 slug 查找当前页面。 */
    private ExistingPage findBySlug(String slug) {
        return jdbc.sql("select id, title, slug, page_type, current_revision_id, content_markdown, content_hash from wiki_pages where slug = :slug and status = 'PUBLISHED'")
                .param("slug", slug).query(PageService::mapExisting).optional().orElse(null);
    }

    /** 按 ID 查找当前页面。 */
    private ExistingPage findById(UUID id) {
        return jdbc.sql("select id, title, slug, page_type, current_revision_id, content_markdown, content_hash from wiki_pages where id = :id and status = 'PUBLISHED'")
                .param("id", id).query(PageService::mapExisting).optional().orElseThrow(() ->
                        new ApiException(HttpStatus.NOT_FOUND, "PAGE_NOT_FOUND", "知识页面不存在"));
    }

    /** 校验必填文本并去除首尾空白。 */
    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
        }
        return value.trim();
    }

    /** 将页面类型限制在稳定集合内。 */
    private String normalizePageType(String value) {
        String type = value == null ? "TOPIC" : value.trim().toUpperCase();
        return switch (type) {
            case "README", "CONTEXT", "ENTITY", "TOPIC", "SYNTHESIS", "QUERY" -> type;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_TYPE", "不支持的页面类型");
        };
    }

    /** 映射页面概要。 */
    private static PageSummary mapSummary(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new PageSummary((UUID) rs.getObject("id"), rs.getString("slug"), rs.getString("title"),
                rs.getString("page_type"), rs.getInt("revision_no"), rs.getString("excerpt"),
                rs.getTimestamp("updated_at").toInstant());
    }

    /** 映射页面正文行。 */
    private static PageRow mapPage(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new PageRow((UUID) rs.getObject("id"), rs.getString("slug"), rs.getString("title"),
                rs.getString("page_type"), rs.getInt("revision_no"), (UUID) rs.getObject("current_revision_id"),
                rs.getString("content_markdown"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /** 映射证据。 */
    private static EvidenceItem mapEvidence(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new EvidenceItem((UUID) rs.getObject("id"), (UUID) rs.getObject("source_id"),
                rs.getString("source_title"), rs.getString("source_type"), rs.getString("canonical_uri"),
                rs.getString("quote_text"), rs.getString("locator"));
    }

    /** 映射反向链接。 */
    private static Backlink mapBacklink(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Backlink((UUID) rs.getObject("id"), rs.getString("slug"), rs.getString("title"),
                rs.getString("relation_type"));
    }

    private static RelatedPage mapRelatedPage(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RelatedPage((UUID) rs.getObject("id"), rs.getString("slug"), rs.getString("title"),
                rs.getString("page_type"), rs.getString("relation_type"));
    }

    /** 映射页面关联的最近审核与修改记录。 */
    private static PageHistoryItem mapHistory(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        java.sql.Timestamp resolved = rs.getTimestamp("resolved_at");
        return new PageHistoryItem((UUID) rs.getObject("id"), rs.getString("title"), rs.getString("summary"),
                rs.getString("status"), rs.getString("change_type"), rs.getString("proposed_by"),
                rs.getString("reviewer_name"), rs.getString("decision"), rs.getString("comment"),
                rs.getTimestamp("created_at").toInstant(), resolved == null ? null : resolved.toInstant());
    }

    /** 映射既有页面并锁定审核基线引用。 */
    private static ExistingPage mapExisting(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ExistingPage((UUID) rs.getObject("id"), rs.getString("title"),
                rs.getString("slug"), rs.getString("page_type"), (UUID) rs.getObject("current_revision_id"),
                rs.getString("content_markdown"), rs.getString("content_hash"));
    }

    private record PageRow(UUID id, String slug, String title, String pageType, int revisionNo,
                           UUID currentRevisionId, String content, Instant createdAt, Instant updatedAt) { }
    private record ExistingPage(UUID id, String title, String slug, String pageType, UUID currentRevisionId,
                                String content, String contentHash) { }

    /** 页面列表项。 */
    public record PageSummary(UUID id, String slug, String title, String pageType, int revisionNo,
                              String excerpt, Instant updatedAt) { }
    /** 页面证据项。 */
    public record EvidenceItem(UUID id, UUID sourceId, String sourceTitle, String sourceType, String canonicalUri,
                               String quote, String locator) { }
    /** 指向当前页面的反向链接。 */
    public record Backlink(UUID pageId, String slug, String title, String relationType) { }
    /** 当前页面主动关联的页面，可从详情直接发起移除关联审核。 */
    public record RelatedPage(UUID pageId, String slug, String title, String pageType, String relationType) { }
    /** 页面最近的审核与修改记录，供知识详情右侧追溯和跳转审核中心。 */
    public record PageHistoryItem(UUID changeSetId, String title, String summary, String status,
                                  String changeType, String proposedBy, String reviewer, String decision,
                                  String comment, Instant createdAt, Instant resolvedAt) { }
    /** 页面详情。 */
    public record PageDetail(UUID id, String slug, String title, String pageType, int revisionNo,
                             UUID currentRevisionId, String contentMarkdown, Instant createdAt, Instant updatedAt,
                             List<EvidenceItem> evidence, List<Backlink> backlinks, List<RelatedPage> relatedPages,
                             List<PageHistoryItem> history) { }
    /** 页面新增或更新请求。 */
    public record PageMutationRequest(UUID pageId, String slug, String title, String pageType, String contentMarkdown) { }
    /** 页面写入结果。 */
    public record MutationResult(String status, UUID pageId, UUID changeSetId, String message) { }
}
