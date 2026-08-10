package com.llmwiki.review;

import com.llmwiki.audit.AuditService;
import com.llmwiki.cache.WikiCacheService;
import com.llmwiki.common.ApiException;
import com.llmwiki.security.AuthenticatedUser;
import com.llmwiki.security.RequestContext;
import com.llmwiki.security.TenantDatabaseContext;
import com.llmwiki.wiki.WikiRelationIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理审核队列并以短事务合并候选变更。
 * 合并时锁定变更集和目标页面、校验基线修订，防止过期提案覆盖较新的知识。
 */
@Service
public class ReviewService {
    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    private final JdbcClient jdbc;
    private final TenantDatabaseContext tenantDatabaseContext;
    private final AuditService auditService;
    private final WikiCacheService cacheService;
    /** 审核完成后从已发布正文统一重建关系派生索引。 */
    private final WikiRelationIndexer relationIndexer;

    /** 创建审核服务。 */
    public ReviewService(JdbcClient jdbc, TenantDatabaseContext tenantDatabaseContext,
                         AuditService auditService, WikiCacheService cacheService,
                         WikiRelationIndexer relationIndexer) {
        this.jdbc = jdbc;
        this.tenantDatabaseContext = tenantDatabaseContext;
        this.auditService = auditService;
        this.cacheService = cacheService;
        this.relationIndexer = relationIndexer;
    }

    /**
     * 列出当前空间待审核变更，按高风险和等待时间排序。
     *
     * @param limit 最大返回条数
     * @return 审核队列
     */
    @Transactional(readOnly = true)
    public List<ChangeSetSummary> listPending(int limit) {
        return listPending(limit, null);
    }

    /** 列出待审核变更，可按页面过滤以支持从知识详情精准跳转审核中心。 */
    @Transactional(readOnly = true)
    public List<ChangeSetSummary> listPending(int limit, UUID pageId) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        String filter = pageId == null ? "" : " and exists (select 1 from change_set_actions filter_action where filter_action.change_set_id = cs.id and filter_action.page_id = :pageId) ";
        var statement = jdbc.sql("""
                        select cs.id, cs.title, cs.summary, cs.status, cs.risk, cs.change_type,
                               coalesce(s.title, '手工提案') as source_title,
                               cs.created_at, u.display_name as proposed_by,
                               count(a.id) as action_count,
                               count(*) filter (where a.action_type = 'UPDATE_PAGE') as update_count
                        from change_sets cs
                        join users u on u.id = cs.proposed_by
                        left join sources s on s.id = cs.source_id
                        left join change_set_actions a on a.change_set_id = cs.id
                        where cs.organization_id = :organizationId and cs.workspace_id = :workspaceId
                          and cs.status = 'PENDING'
                        group by cs.id, u.display_name, s.title
                        order by case cs.risk when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end,
                                 cs.created_at
                        limit :limit
                        """.replace("and cs.status = 'PENDING'", "and cs.status = 'PENDING'" + filter));
        statement = statement.param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("limit", Math.max(1, Math.min(limit, 100)));
        if (pageId != null) statement = statement.param("pageId", pageId);
        return statement.query(ReviewService::mapSummary).list();
    }

    /**
     * 读取审核详情和逐页前后版本，供前端生成差异视图。
     *
     * @param changeSetId 变更集 ID
     * @return 审核详情
     */
    @Transactional(readOnly = true)
    public ChangeSetDetail get(UUID changeSetId) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        ChangeSetHeader header = findHeader(changeSetId, user);
        List<ActionDetail> actions = jdbc.sql("""
                        select a.id, a.action_type, a.page_id, a.base_revision_id, a.proposed_slug,
                               a.proposed_title, a.proposed_page_type, a.proposed_content_markdown,
                               coalesce(r.title, '') as base_title, coalesce(r.content_markdown, '') as base_content
                        from change_set_actions a
                        left join wiki_page_revisions r on r.id = a.base_revision_id
                        where a.change_set_id = :changeSetId
                        order by a.ordinal
                        """)
                .param("changeSetId", changeSetId).query(ReviewService::mapActionDetail).list();
        return new ChangeSetDetail(header.id(), header.title(), header.summary(), header.status(), header.risk(),
                header.changeType(), header.proposedBy(), header.sourceId(), header.sourceTitle(),
                header.createdAt(), header.resolvedAt(), actions);
    }

    /**
     * 批准变更集并生成不可变页面修订。
     * 如果目标页已偏离提案基线，则原子标记为 SUPERSEDED 而不是覆盖新知识。
     *
     * @param changeSetId 变更集 ID
     * @param comment 审核意见
     * @return 合并结果
     */
    @Transactional
    public ReviewResult approve(UUID changeSetId, String comment) {
        AuthenticatedUser reviewer = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        ChangeSetHeader header = lockPendingHeader(changeSetId, reviewer);
        List<ActionRow> actions = loadActions(changeSetId);
        if (actions.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "EMPTY_CHANGE_SET", "变更集没有可执行动作");
        }

        List<ActionRow> updates = actions.stream().filter(action -> "UPDATE_PAGE".equals(action.actionType()) || "ARCHIVE_PAGE".equals(action.actionType()))
                .sorted(Comparator.comparing(action -> action.pageId().toString())).toList();
        for (ActionRow action : updates) {
            CurrentPage page = lockPage(action.pageId());
            if (!page.currentRevisionId().equals(action.baseRevisionId())) {
                jdbc.sql("update change_sets set status = 'SUPERSEDED', resolved_at = now(), updated_at = now(), version = version + 1 where id = :id")
                        .param("id", changeSetId).update();
                insertReview(changeSetId, reviewer, "REJECT", "基线版本已变化，提案自动失效。" + safeComment(comment));
                auditService.record("CHANGE_SET_SUPERSEDED", "CHANGE_SET", changeSetId,
                        Map.of("baseRevisionId", action.baseRevisionId()),
                        Map.of("currentRevisionId", page.currentRevisionId()), Map.of("pageId", page.id()));
                log.info("Change set superseded changeSetId={} pageId={} reviewerId={}",
                        changeSetId, page.id(), reviewer.userId());
                return new ReviewResult("SUPERSEDED", changeSetId, List.of(), "目标页面已有更新，请基于最新版本重新提交");
            }
        }

        List<PublishedPage> published = new ArrayList<>();
        for (ActionRow action : actions) {
            if ("CREATE_PAGE".equals(action.actionType())) {
                published.add(applyCreate(action, header, reviewer));
            } else if ("UPDATE_PAGE".equals(action.actionType())) {
                published.add(applyUpdate(action, header, reviewer));
            } else if ("ARCHIVE_PAGE".equals(action.actionType())) {
                published.add(applyArchive(action, reviewer));
            }
        }
        for (PublishedPage page : published) {
            relationIndexer.rebuildOutgoing(page.pageId(), page.content());
        }
        insertReview(changeSetId, reviewer, "APPROVE", safeComment(comment));
        jdbc.sql("""
                        update change_sets set status = 'APPROVED', resolved_at = now(), updated_at = now(), version = version + 1
                        where id = :id and status = 'PENDING'
                        """).param("id", changeSetId).update();
        List<UUID> pageIds = published.stream().map(PublishedPage::pageId).toList();
        auditService.record("CHANGE_SET_APPROVED", "CHANGE_SET", changeSetId,
                Map.of("status", "PENDING"), Map.of("status", "APPROVED", "pageIds", pageIds),
                Map.of("reviewerId", reviewer.userId()));
        auditService.outbox("CHANGE_SET", changeSetId, "wiki.change-set.approved", Map.of("pageIds", pageIds));
        cacheService.advanceWorkspaceEpoch(reviewer.organizationId(), reviewer.workspaceId());
        log.info("Change set approved changeSetId={} publishedPages={} reviewerId={}",
                changeSetId, pageIds.size(), reviewer.userId());
        return new ReviewResult("APPROVED", changeSetId, pageIds, "变更已审核并发布");
    }

    /**
     * 驳回待审核变更，不修改任何已发布页面。
     *
     * @param changeSetId 变更集 ID
     * @param comment 必填审核意见
     * @return 驳回结果
     */
    @Transactional
    public ReviewResult reject(UUID changeSetId, String comment) {
        AuthenticatedUser reviewer = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        lockPendingHeader(changeSetId, reviewer);
        if (comment == null || comment.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REVIEW_COMMENT_REQUIRED", "驳回时必须填写原因");
        }
        insertReview(changeSetId, reviewer, "REJECT", comment.trim());
        jdbc.sql("""
                        update change_sets set status = 'REJECTED', resolved_at = now(), updated_at = now(), version = version + 1
                        where id = :id and status = 'PENDING'
                        """).param("id", changeSetId).update();
        auditService.record("CHANGE_SET_REJECTED", "CHANGE_SET", changeSetId,
                Map.of("status", "PENDING"), Map.of("status", "REJECTED"), Map.of("comment", comment.trim()));
        auditService.outbox("CHANGE_SET", changeSetId, "wiki.change-set.rejected", Map.of("comment", comment.trim()));
        log.info("Change set rejected changeSetId={} reviewerId={}", changeSetId, reviewer.userId());
        return new ReviewResult("REJECTED", changeSetId, List.of(), "变更已驳回");
    }

    /** 为 CREATE_PAGE 动作创建页面、修订和来源证据。 */
    private PublishedPage applyCreate(ActionRow action, ChangeSetHeader header, AuthenticatedUser reviewer) {
        boolean exists = jdbc.sql("select exists(select 1 from wiki_pages where slug = :slug and status = 'PUBLISHED')")
                .param("slug", action.slug()).query(Boolean.class).single();
        if (exists) {
            throw new ApiException(HttpStatus.CONFLICT, "PAGE_SLUG_CONFLICT", "页面标识已存在：" + action.slug());
        }
        UUID pageId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        jdbc.sql("""
                        insert into wiki_pages(id, organization_id, workspace_id, slug, title, page_type, revision_no,
                                               content_markdown, content_hash, created_by, updated_by)
                        values (:id, :organizationId, :workspaceId, :slug, :title, :pageType, 1,
                                :content, :hash, :proposedBy, :reviewerId)
                        """)
                .param("id", pageId).param("organizationId", reviewer.organizationId())
                .param("workspaceId", reviewer.workspaceId()).param("slug", action.slug()).param("title", action.title())
                .param("pageType", action.pageType()).param("content", action.content()).param("hash", action.contentHash())
                .param("proposedBy", header.proposedById()).param("reviewerId", reviewer.userId()).update();
        insertRevision(revisionId, pageId, 1, action, header, reviewer);
        jdbc.sql("update wiki_pages set current_revision_id = :revisionId where id = :pageId")
                .param("revisionId", revisionId).param("pageId", pageId).update();
        insertEvidence(revisionId, header.sourceId(), null);
        return new PublishedPage(pageId, revisionId, action.content());
    }

    /** 为 UPDATE_PAGE 动作追加修订并以乐观版本条件更新当前投影。 */
    private PublishedPage applyUpdate(ActionRow action, ChangeSetHeader header, AuthenticatedUser reviewer) {
        CurrentPage page = lockPage(action.pageId());
        int nextRevision = page.revisionNo() + 1;
        UUID revisionId = UUID.randomUUID();
        insertRevision(revisionId, page.id(), nextRevision, action, header, reviewer);
        int updated = jdbc.sql("""
                        update wiki_pages
                        set slug = :slug, title = :title, page_type = :pageType, current_revision_id = :revisionId,
                            revision_no = :revisionNo, content_markdown = :content, content_hash = :hash,
                            updated_by = :reviewerId, updated_at = now(), version = version + 1,
                            last_evolution_scanned_at = null
                        where id = :pageId and version = :version and current_revision_id = :baseRevisionId
                        """)
                .param("slug", action.slug()).param("title", action.title()).param("pageType", action.pageType())
                .param("revisionId", revisionId).param("revisionNo", nextRevision).param("content", action.content())
                .param("hash", action.contentHash()).param("reviewerId", reviewer.userId()).param("pageId", page.id())
                .param("version", page.version()).param("baseRevisionId", action.baseRevisionId()).update();
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_PAGE_UPDATE", "页面在审核过程中被其他事务修改");
        }
        insertEvidence(revisionId, header.sourceId(), action.baseRevisionId());
        return new PublishedPage(page.id(), revisionId, action.content());
    }

    /** 将页面归档但保留全部修订和审计历史。 */
    private PublishedPage applyArchive(ActionRow action, AuthenticatedUser reviewer) {
        CurrentPage page = lockPage(action.pageId());
        jdbc.sql("update wiki_pages set status = 'ARCHIVED', updated_by = :userId, updated_at = now(), version = version + 1 where id = :id")
                .param("userId", reviewer.userId()).param("id", page.id()).update();
        return new PublishedPage(page.id(), page.currentRevisionId(), "");
    }

    /** 插入不可变页面修订。 */
    private void insertRevision(UUID revisionId, UUID pageId, int revisionNo, ActionRow action,
                                ChangeSetHeader header, AuthenticatedUser reviewer) {
        jdbc.sql("""
                        insert into wiki_page_revisions(id, organization_id, workspace_id, page_id, revision_no,
                                                        title, content_markdown, content_hash, change_summary,
                                                        change_set_id, published_by)
                        values (:id, :organizationId, :workspaceId, :pageId, :revisionNo, :title, :content,
                                :hash, :summary, :changeSetId, :reviewerId)
                        """)
                .param("id", revisionId).param("organizationId", reviewer.organizationId())
                .param("workspaceId", reviewer.workspaceId()).param("pageId", pageId).param("revisionNo", revisionNo)
                .param("title", action.title()).param("content", action.content()).param("hash", action.contentHash())
                .param("summary", header.summary()).param("changeSetId", header.id()).param("reviewerId", reviewer.userId())
                .update();
    }

    /**
     * 将明确新来源关联为证据；持续优化没有新来源时复制基线修订证据，避免审核发布后丢失可追溯链。
     */
    private void insertEvidence(UUID revisionId, UUID sourceId, UUID baseRevisionId) {
        if (sourceId == null && baseRevisionId != null) {
            jdbc.sql("""
                            insert into evidence(organization_id, workspace_id, page_revision_id, source_id,
                                                 source_version_id, quote_text, locator)
                            select organization_id, workspace_id, :revisionId, source_id, source_version_id,
                                   quote_text, locator
                            from evidence where page_revision_id = :baseRevisionId
                            """).param("revisionId", revisionId).param("baseRevisionId", baseRevisionId).update();
            return;
        }
        if (sourceId == null) {
            return;
        }
        jdbc.sql("""
                        insert into evidence(organization_id, workspace_id, page_revision_id, source_id,
                                             source_version_id, quote_text, locator)
                        select s.organization_id, s.workspace_id, :revisionId, s.id, sv.id,
                               left(sv.extracted_markdown, 500), coalesce(s.canonical_uri, '')
                        from sources s join source_versions sv on sv.id = s.latest_version_id
                        where s.id = :sourceId
                        """).param("revisionId", revisionId).param("sourceId", sourceId).update();
    }

    /** 锁定待审核变更集，保证同一提案只能被决策一次。 */
    private ChangeSetHeader lockPendingHeader(UUID changeSetId, AuthenticatedUser user) {
        return jdbc.sql("""
                        select cs.id, cs.title, cs.summary, cs.status, cs.risk, cs.change_type,
                               cs.proposed_by, u.display_name as proposed_by_name, cs.source_id,
                               coalesce(s.title, '手工提案') as source_title, cs.created_at, cs.resolved_at
                        from change_sets cs join users u on u.id = cs.proposed_by left join sources s on s.id = cs.source_id
                        where cs.id = :id and cs.organization_id = :organizationId and cs.workspace_id = :workspaceId
                          and cs.status = 'PENDING'
                        for update of cs
                        """)
                .param("id", changeSetId).param("organizationId", user.organizationId())
                .param("workspaceId", user.workspaceId()).query(ReviewService::mapHeader).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "CHANGE_SET_NOT_PENDING", "变更集不存在或已完成审核"));
    }

    /** 读取任意状态变更集详情头。 */
    private ChangeSetHeader findHeader(UUID changeSetId, AuthenticatedUser user) {
        return jdbc.sql("""
                        select cs.id, cs.title, cs.summary, cs.status, cs.risk, cs.change_type,
                               cs.proposed_by, u.display_name as proposed_by_name, cs.source_id,
                               coalesce(s.title, '手工提案') as source_title, cs.created_at, cs.resolved_at
                        from change_sets cs join users u on u.id = cs.proposed_by left join sources s on s.id = cs.source_id
                        where cs.id = :id and cs.organization_id = :organizationId and cs.workspace_id = :workspaceId
                        """)
                .param("id", changeSetId).param("organizationId", user.organizationId())
                .param("workspaceId", user.workspaceId()).query(ReviewService::mapHeader).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CHANGE_SET_NOT_FOUND", "审核变更不存在"));
    }

    /** 读取待合并动作。 */
    private List<ActionRow> loadActions(UUID changeSetId) {
        return jdbc.sql("""
                        select id, action_type, page_id, base_revision_id, proposed_slug, proposed_title,
                               proposed_page_type, proposed_content_markdown, content_hash, ordinal
                        from change_set_actions where change_set_id = :changeSetId order by ordinal
                        """).param("changeSetId", changeSetId).query(ReviewService::mapAction).list();
    }

    /** 以固定 ID 顺序锁定页面，调用方先排序可避免多页审核死锁。 */
    private CurrentPage lockPage(UUID pageId) {
        return jdbc.sql("select id, current_revision_id, revision_no, version from wiki_pages where id = :id for update")
                .param("id", pageId).query((rs, rowNum) -> new CurrentPage((UUID) rs.getObject("id"),
                        (UUID) rs.getObject("current_revision_id"), rs.getInt("revision_no"), rs.getLong("version")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "PAGE_NOT_FOUND", "目标页面已不存在"));
    }

    /** 插入审核决策，唯一约束阻止同一审核人重复决策。 */
    private void insertReview(UUID changeSetId, AuthenticatedUser reviewer, String decision, String comment) {
        jdbc.sql("""
                        insert into reviews(organization_id, workspace_id, change_set_id, reviewer_id, decision, comment)
                        values (:organizationId, :workspaceId, :changeSetId, :reviewerId, :decision, :comment)
                        """)
                .param("organizationId", reviewer.organizationId()).param("workspaceId", reviewer.workspaceId())
                .param("changeSetId", changeSetId).param("reviewerId", reviewer.userId())
                .param("decision", decision).param("comment", comment).update();
    }

    /** 将可选审核意见规范为空字符串或去空白文本。 */
    private String safeComment(String comment) {
        return comment == null ? "" : comment.trim();
    }

    /** 映射列表项。 */
    private static ChangeSetSummary mapSummary(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ChangeSetSummary((UUID) rs.getObject("id"), rs.getString("title"), rs.getString("summary"),
                rs.getString("status"), rs.getString("source_title"), rs.getString("change_type"),
                rs.getString("proposed_by"), rs.getInt("action_count"), rs.getInt("update_count"),
                rs.getTimestamp("created_at").toInstant());
    }

    /** 映射变更集头。 */
    private static ChangeSetHeader mapHeader(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        java.sql.Timestamp resolved = rs.getTimestamp("resolved_at");
        return new ChangeSetHeader((UUID) rs.getObject("id"), rs.getString("title"), rs.getString("summary"),
                rs.getString("status"), rs.getString("risk"), rs.getString("change_type"),
                (UUID) rs.getObject("proposed_by"), rs.getString("proposed_by_name"), (UUID) rs.getObject("source_id"),
                rs.getString("source_title"),
                rs.getTimestamp("created_at").toInstant(), resolved == null ? null : resolved.toInstant());
    }

    /** 映射审核动作详情。 */
    private static ActionDetail mapActionDetail(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ActionDetail((UUID) rs.getObject("id"), rs.getString("action_type"),
                (UUID) rs.getObject("page_id"), (UUID) rs.getObject("base_revision_id"), rs.getString("proposed_slug"),
                rs.getString("proposed_title"), rs.getString("proposed_page_type"),
                rs.getString("base_title"), rs.getString("base_content"), rs.getString("proposed_content_markdown"));
    }

    /** 映射可执行动作。 */
    private static ActionRow mapAction(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ActionRow((UUID) rs.getObject("id"), rs.getString("action_type"), (UUID) rs.getObject("page_id"),
                (UUID) rs.getObject("base_revision_id"), rs.getString("proposed_slug"), rs.getString("proposed_title"),
                rs.getString("proposed_page_type"), rs.getString("proposed_content_markdown"),
                rs.getString("content_hash"), rs.getInt("ordinal"));
    }

    private record ChangeSetHeader(UUID id, String title, String summary, String status, String risk,
                                   String changeType, UUID proposedById, String proposedBy,
                                   UUID sourceId, String sourceTitle, Instant createdAt, Instant resolvedAt) { }
    private record ActionRow(UUID id, String actionType, UUID pageId, UUID baseRevisionId, String slug,
                             String title, String pageType, String content, String contentHash, int ordinal) { }
    private record CurrentPage(UUID id, UUID currentRevisionId, int revisionNo, long version) { }
    private record PublishedPage(UUID pageId, UUID revisionId, String content) { }

    /** 审核队列列表项。 */
    public record ChangeSetSummary(UUID id, String title, String summary, String status, String sourceTitle,
                                   String changeType, String proposedBy, int actionCount, int updateCount,
                                   Instant createdAt) { }
    /** 单个页面动作的前后内容。 */
    public record ActionDetail(UUID id, String actionType, UUID pageId, UUID baseRevisionId, String proposedSlug,
                               String proposedTitle, String proposedPageType, String baseTitle,
                               String baseContentMarkdown, String proposedContentMarkdown) { }
    /** 审核详情。 */
    public record ChangeSetDetail(UUID id, String title, String summary, String status, String risk,
                                  String changeType, String proposedBy, UUID sourceId, String sourceTitle,
                                  Instant createdAt, Instant resolvedAt, List<ActionDetail> actions) { }
    /** 审核决策结果。 */
    public record ReviewResult(String status, UUID changeSetId, List<UUID> pageIds, String message) { }
}
