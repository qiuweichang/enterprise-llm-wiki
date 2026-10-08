package com.llmwiki.background;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmwiki.audit.AuditService;
import com.llmwiki.cache.WikiCacheService;
import com.llmwiki.common.ContentHash;
import com.llmwiki.config.LlmWikiProperties;
import com.llmwiki.security.TenantDatabaseContext;
import com.llmwiki.source.LocalObjectStorage;
import com.llmwiki.wiki.WikiRelationIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 负责摄取任务的原子租约、租户内读取、完成提交与指数退避失败处理。
 */
@Service
public class IngestionJobService {
    private static final Logger log = LoggerFactory.getLogger(IngestionJobService.class);
    private final JdbcClient jdbc;
    private final TenantDatabaseContext tenantDatabaseContext;
    private final AuditService auditService;
    private final WikiCacheService cacheService;
    private final ObjectMapper objectMapper;
    private final LlmWikiProperties properties;
    private final LocalObjectStorage objectStorage;
    /** 自动发布完成后统一建立关系，确保同批目标页面已经全部存在。 */
    private final WikiRelationIndexer relationIndexer;

    /** 创建摄取任务服务。 */
    public IngestionJobService(JdbcClient jdbc, TenantDatabaseContext tenantDatabaseContext,
                               AuditService auditService, WikiCacheService cacheService, ObjectMapper objectMapper,
                               LlmWikiProperties properties, LocalObjectStorage objectStorage,
                               WikiRelationIndexer relationIndexer) {
        this.jdbc = jdbc;
        this.tenantDatabaseContext = tenantDatabaseContext;
        this.auditService = auditService;
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.objectStorage = objectStorage;
        this.relationIndexer = relationIndexer;
    }

    /**
     * 使用 UPDATE ... FOR UPDATE SKIP LOCKED 原子领取一个任务。
     * 事务提交后不再持有行锁，长耗时外部调用仅受租约保护。
     *
     * @return 成功领取的任务，队列为空时为 null
     */
    @Transactional
    public JobLease claim() {
        return jdbc.sql("""
                        with candidate as (
                            select id from ingestion_jobs
                            where attempts < max_attempts
                              and ((status = 'PENDING' and next_attempt_at <= now())
                                   or (status = 'PROCESSING' and lease_until < now()))
                            order by next_attempt_at, created_at
                            limit 1 for update skip locked
                        )
                        update ingestion_jobs j
                        set status = 'PROCESSING', attempts = attempts + 1, lease_owner = :owner,
                            lease_until = now() + cast(:leaseSeconds || ' seconds' as interval),
                            started_at = coalesce(started_at, now()), error_message = null
                        from candidate c where j.id = c.id
                        returning j.id, j.organization_id, j.workspace_id, j.source_id, j.requested_by,
                                  j.attempts, j.max_attempts, j.payload::text
                        """)
                .param("owner", properties.worker().instanceId())
                .param("leaseSeconds", Long.toString(properties.worker().leaseDuration().toSeconds()))
                .query(IngestionJobService::mapLease).optional().orElse(null);
    }

    /**
     * 在短只读事务内加载来源、现有版本和同名页面上下文。
     *
     * @param lease 已领取任务
     * @return 处理所需快照
     */
    @Transactional(readOnly = true)
    public JobMaterial loadMaterial(JobLease lease) {
        tenantDatabaseContext.apply(lease.organizationId(), lease.workspaceId());
        SourceRow source = jdbc.sql("""
                        select s.id, s.source_type, s.title, s.canonical_uri, s.latest_version_id,
                               sv.extracted_markdown, sv.content_hash, sv.raw_object_key
                        from sources s left join source_versions sv on sv.id = s.latest_version_id
                        where s.id = :sourceId
                        """).param("sourceId", lease.sourceId()).query(IngestionJobService::mapSource).single();
        String expectedSlug = com.llmwiki.common.Slugifier.slugify(source.title());
        KnowledgeCompiler.CurrentPage currentPage = jdbc.sql("""
                        select id, current_revision_id, slug, title, page_type, content_markdown
                        from wiki_pages where slug = :slug and status = 'PUBLISHED'
                        """).param("slug", expectedSlug).query((rs, rowNum) -> new KnowledgeCompiler.CurrentPage(
                        (UUID) rs.getObject("id"), (UUID) rs.getObject("current_revision_id"), rs.getString("slug"),
                        rs.getString("title"), rs.getString("page_type"), rs.getString("content_markdown")))
                .optional().orElse(null);
        return new JobMaterial(lease, source, currentPage, parsePayload(lease.payloadJson()));
    }

    /**
     * 原子持久化提取版本、候选变更、发布策略结果、审计和任务完成状态。
     *
     * @param material 处理快照
     * @param extraction 提取结果
     * @param compiled 编译候选页面
     */
    @Transactional
    public void complete(JobMaterial material, PythonExtractionClient.ExtractionResult extraction,
                         KnowledgeCompiler.CompilationResult compiled) {
        JobLease lease = material.lease();
        tenantDatabaseContext.apply(lease.organizationId(), lease.workspaceId());
        if (compiled.pages().isEmpty()) {
            throw new IllegalStateException("知识编译结果没有页面");
        }
        UUID sourceVersionId = material.source().latestVersionId();
        String markdown = extraction.markdown();
        String hash = extraction.contentHash() == null || extraction.contentHash().isBlank()
                ? ContentHash.sha256(markdown) : extraction.contentHash();
        if (!hash.equals(material.source().contentHash()) || sourceVersionId == null) {
            int nextVersion = jdbc.sql("select coalesce(max(version_no), 0) + 1 from source_versions where source_id = :sourceId")
                    .param("sourceId", lease.sourceId()).query(Integer.class).single();
            sourceVersionId = UUID.randomUUID();
            jdbc.sql("""
                            insert into source_versions(id, organization_id, workspace_id, source_id, version_no,
                                                        raw_object_key, extracted_markdown, content_hash,
                                                        extraction_metadata, created_by)
                            values (:id, :organizationId, :workspaceId, :sourceId, :versionNo, :objectKey,
                                    :markdown, :hash, cast(:metadata as jsonb), :userId)
                            """)
                    .param("id", sourceVersionId).param("organizationId", lease.organizationId())
                    .param("workspaceId", lease.workspaceId()).param("sourceId", lease.sourceId())
                    .param("versionNo", nextVersion).param("objectKey", material.payload().get("objectKey"))
                    .param("markdown", markdown).param("hash", hash).param("metadata", json(extraction.metadata()))
                    .param("userId", lease.requestedBy()).update();
        }
        jdbc.sql("""
                        update sources set latest_version_id = :versionId, content_hash = :hash,
                                           status = 'READY', updated_at = now(), version = version + 1
                        where id = :sourceId
                        """).param("versionId", sourceVersionId).param("hash", hash).param("sourceId", lease.sourceId()).update();

        List<PageCandidate> candidates = compiled.pages().stream()
                .map(page -> candidateFor(material.source(), page))
                .toList();
        UUID changeSetId = UUID.randomUUID();
        boolean containsUpdate = candidates.stream().anyMatch(candidate -> candidate.currentPage() != null);
        String status = !containsUpdate && canAutoPublishNew(lease) ? "APPROVED" : "PENDING";
        KnowledgeCompiler.CompiledPage rootPage = compiled.pages().getFirst();
        jdbc.sql("""
                        insert into change_sets(id, organization_id, workspace_id, change_type, status, title, summary,
                                                risk, source_id, proposed_by, submitted_at, resolved_at)
                        values (:id, :organizationId, :workspaceId, 'INGESTION', :status, :title, :summary,
                                :risk, :sourceId, :userId, now(),
                                case when :status = 'APPROVED' then now() else null end)
                        """).param("id", changeSetId).param("organizationId", lease.organizationId())
                .param("workspaceId", lease.workspaceId()).param("status", status).param("title", rootPage.title())
                .param("summary", compiled.summary()).param("risk", compiled.risk())
                .param("sourceId", lease.sourceId()).param("userId", lease.requestedBy()).update();
        for (int ordinal = 0; ordinal < candidates.size(); ordinal++) {
            PageCandidate candidate = candidates.get(ordinal);
            KnowledgeCompiler.CurrentPage existing = candidate.currentPage();
            KnowledgeCompiler.CompiledPage page = candidate.page();
            String actionType = existing == null ? "CREATE_PAGE" : "UPDATE_PAGE";
            jdbc.sql("""
                            insert into change_set_actions(organization_id, workspace_id, change_set_id, action_type,
                                                           page_id, base_revision_id, proposed_slug, proposed_title,
                                                           proposed_page_type, proposed_content_markdown, content_hash, ordinal)
                            values (:organizationId, :workspaceId, :changeSetId, :actionType, :pageId, :baseRevisionId,
                                    :slug, :title, :pageType, :content, :hash, :ordinal)
                            """).param("organizationId", lease.organizationId()).param("workspaceId", lease.workspaceId())
                    .param("changeSetId", changeSetId).param("actionType", actionType)
                    .param("pageId", existing == null ? null : existing.id())
                    .param("baseRevisionId", existing == null ? null : existing.currentRevisionId())
                    .param("slug", page.slug()).param("title", page.title()).param("pageType", page.pageType())
                    .param("content", page.markdown()).param("hash", ContentHash.sha256(page.markdown()))
                    .param("ordinal", ordinal).update();
        }

        List<PublishedPage> publishedPages = new ArrayList<>();
        if ("APPROVED".equals(status)) {
            for (KnowledgeCompiler.CompiledPage page : compiled.pages()) {
                publishedPages.add(publishNewPage(lease, changeSetId, sourceVersionId, page, compiled.summary()));
            }
            for (PublishedPage page : publishedPages) {
                relationIndexer.rebuildOutgoing(page.pageId(), page.content());
            }
            cacheService.advanceWorkspaceEpoch(lease.organizationId(), lease.workspaceId());
        }
        jdbc.sql("""
                        update ingestion_jobs set status = 'SUCCEEDED', lease_owner = null, lease_until = null,
                                                  finished_at = now(), error_message = null
                        where id = :jobId and lease_owner = :owner
                        """).param("jobId", lease.id()).param("owner", properties.worker().instanceId()).update();
        updateAiTaskRun(material.payload().get("aiTaskRunId"),
                "大模型结果已完成知识编译，生成的新增知识已进入审核流程；已有知识未绕过审核。", null, false);
        auditService.record(lease.organizationId(), lease.workspaceId(), lease.requestedBy(), "SOURCE_COMPILED", "SOURCE",
                lease.sourceId(), null, Map.of("changeSetId", changeSetId, "status", status),
                Map.of("jobId", lease.id(), "modelGenerated", compiled.modelGenerated(),
                        "pageCount", compiled.pages().size()));
        List<String> publishedPageIds = publishedPages.stream().map(page -> page.pageId().toString()).toList();
        auditService.outbox(lease.organizationId(), lease.workspaceId(), "SOURCE", lease.sourceId(),
                "wiki.source.compiled", Map.of("changeSetId", changeSetId, "status", status,
                        "pageIds", publishedPageIds));
        log.info("Ingestion job completed jobId={} sourceId={} changeSetId={} status={} candidatePages={} publishedPages={}",
                lease.id(), lease.sourceId(), changeSetId, status, candidates.size(), publishedPages.size());
    }

    /**
     * 记录处理失败并按尝试次数回退到 PENDING 或进入 DEAD；死信同时标记来源失败。
     *
     * @param lease 任务租约
     * @param exception 失败原因
     */
    @Transactional
    public void fail(JobLease lease, Exception exception) {
        tenantDatabaseContext.apply(lease.organizationId(), lease.workspaceId());
        String message = hasCause(exception, ModelQuotaExceededException.class)
                ? "模型额度已达到服务商限制，等待额度窗口重置后会自动重试。"
                : hasCause(exception, com.llmwiki.security.ModelCredentialException.class)
                ? "模型接口密钥已失效，请到“模型与 MCP”重新输入密钥并测试保存。"
                : exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        String nextStatus = lease.attempts() >= lease.maxAttempts() ? "DEAD" : "PENDING";
        jdbc.sql("""
                        update ingestion_jobs
                        set status = :status, lease_owner = null, lease_until = null,
                            next_attempt_at = now() + cast(least(power(2, attempts), 300)::text || ' seconds' as interval),
                            error_message = left(:error, 2000), finished_at = case when :status = 'DEAD' then now() else null end
                        where id = :jobId and lease_owner = :owner
                        """).param("status", nextStatus).param("error", message).param("jobId", lease.id())
                .param("owner", properties.worker().instanceId()).update();
        if ("DEAD".equals(nextStatus)) {
            jdbc.sql("update sources set status = 'FAILED', updated_at = now() where id = :sourceId")
                    .param("sourceId", lease.sourceId()).update();
        }
        String aiMessage = "DEAD".equals(nextStatus)
                ? "知识编译失败，资料来源已标记失败：" + message
                : "知识编译暂未完成，将按摄取队列策略自动重试：" + message;
        updateAiTaskRun(readAiTaskRunId(lease), aiMessage, "DEAD".equals(nextStatus) ? message : null, "DEAD".equals(nextStatus));
        log.error("Ingestion job failed jobId={} attempt={}/{} nextStatus={}", lease.id(), lease.attempts(),
                lease.maxAttempts(), nextStatus, exception);
    }

    /** 将 AI 定时任务关联的摄取结果回写到执行记录，避免模型成功但编译失败被隐藏。 */
    private void updateAiTaskRun(Object runIdValue, String summary, String error, boolean failed) {
        if (runIdValue == null || runIdValue.toString().isBlank()) return;
        try {
            UUID runId = UUID.fromString(runIdValue.toString());
            jdbc.sql("update ai_scheduled_task_runs set result_summary = :summary, error_message = :error, status = case when :failed then 'FAILED' else status end, finished_at = case when :failed then now() else finished_at end where id = :runId")
                    .param("summary", summary).param("error", error).param("failed", failed).param("runId", runId).update();
        } catch (IllegalArgumentException exception) {
            log.error("Invalid AI task run id in ingestion payload value={}", runIdValue, exception);
        }
    }

    /** 读取摄取租约 JSON 载荷，提取 AI 定时任务关联 ID。 */
    private Object readAiTaskRunId(JobLease lease) {
        try {
            return parsePayload(lease.payloadJson()).get("aiTaskRunId");
        } catch (RuntimeException exception) {
            log.error("Unable to parse ingestion payload for jobId={}", lease.id(), exception);
            return null;
        }
    }

    /** 沿异常链识别模型调用的可恢复故障类型。 */
    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) { if (type.isInstance(current)) return true; current = current.getCause(); }
        return false;
    }

    /**
     * 直接发布具备权限的新页面，并关联来源证据。
     * 关系索引在同一批页面全部插入后统一执行，避免批内 WikiLink 因目标尚不存在而丢失。
     */
    private PublishedPage publishNewPage(JobLease lease, UUID changeSetId, UUID sourceVersionId,
                                         KnowledgeCompiler.CompiledPage compiled, String summary) {
        UUID pageId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        String hash = ContentHash.sha256(compiled.markdown());
        jdbc.sql("""
                        insert into wiki_pages(id, organization_id, workspace_id, slug, title, page_type, current_revision_id,
                                               revision_no, content_markdown, content_hash, created_by, updated_by)
                        values (:id, :organizationId, :workspaceId, :slug, :title, :pageType, null,
                                1, :content, :hash, :userId, :userId)
                        """).param("id", pageId).param("organizationId", lease.organizationId())
                .param("workspaceId", lease.workspaceId()).param("slug", compiled.slug()).param("title", compiled.title())
                .param("pageType", compiled.pageType()).param("content", compiled.markdown()).param("hash", hash)
                .param("userId", lease.requestedBy()).update();
        jdbc.sql("""
                        insert into wiki_page_revisions(id, organization_id, workspace_id, page_id, revision_no,
                                                        title, content_markdown, content_hash, change_summary,
                                                        change_set_id, published_by)
                        values (:id, :organizationId, :workspaceId, :pageId, 1, :title, :content, :hash,
                                :summary, :changeSetId, :userId)
                        """).param("id", revisionId).param("organizationId", lease.organizationId())
                .param("workspaceId", lease.workspaceId()).param("pageId", pageId).param("title", compiled.title())
                .param("content", compiled.markdown()).param("hash", hash).param("summary", summary)
                .param("changeSetId", changeSetId).param("userId", lease.requestedBy()).update();
        jdbc.sql("update wiki_pages set current_revision_id = :revisionId where id = :pageId")
                .param("revisionId", revisionId).param("pageId", pageId).update();
        jdbc.sql("""
                        insert into evidence(organization_id, workspace_id, page_revision_id, source_id,
                                             source_version_id, quote_text, locator)
                        select :organizationId, :workspaceId, :revisionId, s.id, :sourceVersionId,
                               left(sv.extracted_markdown, 500), coalesce(s.canonical_uri, '')
                        from sources s join source_versions sv on sv.id = :sourceVersionId where s.id = :sourceId
                        """).param("organizationId", lease.organizationId()).param("workspaceId", lease.workspaceId())
                .param("revisionId", revisionId).param("sourceVersionId", sourceVersionId)
                .param("sourceId", lease.sourceId()).update();
        return new PublishedPage(pageId, compiled.markdown());
    }

    /** 按租户会话查找同标识已发布页面，供逐页决定新增或更新动作。 */
    private KnowledgeCompiler.CurrentPage findCurrentPage(String slug) {
        return jdbc.sql("""
                        select id, current_revision_id, slug, title, page_type, content_markdown
                        from wiki_pages where slug = :slug and status = 'PUBLISHED'
                        """).param("slug", slug).query((rs, rowNum) -> new KnowledgeCompiler.CurrentPage(
                        (UUID) rs.getObject("id"), (UUID) rs.getObject("current_revision_id"), rs.getString("slug"),
                        rs.getString("title"), rs.getString("page_type"), rs.getString("content_markdown")))
                .optional().orElse(null);
    }

    /**
     * 为编译页面绑定当前已发布版本，并对厂商资讯采用“保留基线、追加动态”的更新语义。
     * 厂商页承担稳定的模型族谱关系，如果直接用一次资讯摘要整页覆盖，会导致旧 WikiLink 和图谱边丢失。
     *
     * @param source 当前资料来源，用 canonical URI 识别官方厂商资讯任务
     * @param page 模型生成的候选页面
     * @return 带当前页面快照、且必要时已合并正文的审核候选
     */
    private PageCandidate candidateFor(SourceRow source, KnowledgeCompiler.CompiledPage page) {
        KnowledgeCompiler.CurrentPage existing = findCurrentPage(page.slug());
        if (existing == null || source.canonicalUri() == null
                || !source.canonicalUri().startsWith("ai-vendor-news://")) {
            return new PageCandidate(page, existing);
        }
        String updateMarker = "<!-- ai-vendor-news:" + source.canonicalUri() + " -->";
        String merged = existing.content().contains(updateMarker)
                ? existing.content()
                : existing.content().stripTrailing() + "\n\n## 近期动态\n\n" + updateMarker + "\n\n"
                + page.markdown().strip() + "\n";
        KnowledgeCompiler.CompiledPage mergedPage = new KnowledgeCompiler.CompiledPage(
                page.slug(), page.title(), existing.pageType(), merged);
        return new PageCandidate(mergedPage, existing);
    }

    /** 判断任务发起人在当前空间是否仍拥有新增直接发布权限。 */
    private boolean canAutoPublishNew(JobLease lease) {
        return jdbc.sql("""
                        select exists(
                            select 1 from workspace_member_roles wmr
                            join role_permissions rp on rp.role_id = wmr.role_id
                            join workspace_members wm on wm.workspace_id = wmr.workspace_id and wm.user_id = wmr.user_id
                            where wmr.organization_id = :organizationId and wmr.workspace_id = :workspaceId
                              and wmr.user_id = :userId and wm.status = 'ACTIVE'
                              and rp.permission_code = 'PAGE_CREATE_PUBLISH'
                        )
                        """).param("organizationId", lease.organizationId()).param("workspaceId", lease.workspaceId())
                .param("userId", lease.requestedBy()).query(Boolean.class).single();
    }

    /** 将对象键解析为共享绝对路径。 */
    public String resolveObjectPath(JobMaterial material) {
        Object key = material.payload().get("objectKey");
        if (key == null) {
            throw new IllegalStateException("Ingestion job has no objectKey");
        }
        return objectStorage.resolve(key.toString()).toString();
    }

    /** JSON 载荷反序列化。 */
    private Map<String, Object> parsePayload(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid ingestion job payload", exception);
        }
    }

    /** JSON 载荷序列化。 */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize extraction metadata", exception);
        }
    }

    /** 映射租约。 */
    private static JobLease mapLease(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new JobLease((UUID) rs.getObject("id"), (UUID) rs.getObject("organization_id"),
                (UUID) rs.getObject("workspace_id"), (UUID) rs.getObject("source_id"),
                (UUID) rs.getObject("requested_by"), rs.getInt("attempts"), rs.getInt("max_attempts"),
                rs.getString("payload"));
    }

    /** 映射来源处理快照。 */
    private static SourceRow mapSource(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SourceRow((UUID) rs.getObject("id"), rs.getString("source_type"), rs.getString("title"),
                rs.getString("canonical_uri"), (UUID) rs.getObject("latest_version_id"),
                rs.getString("extracted_markdown"), rs.getString("content_hash"), rs.getString("raw_object_key"));
    }

    /** 数据库任务租约。 */
    public record JobLease(UUID id, UUID organizationId, UUID workspaceId, UUID sourceId, UUID requestedBy,
                           int attempts, int maxAttempts, String payloadJson) { }
    /** 来源处理快照。 */
    public record SourceRow(UUID id, String sourceType, String title, String canonicalUri, UUID latestVersionId,
                            String extractedMarkdown, String contentHash, String rawObjectKey) { }
    /** 外部处理所需材料。 */
    public record JobMaterial(JobLease lease, SourceRow source, KnowledgeCompiler.CurrentPage currentPage,
                              Map<String, Object> payload) { }
    /** 编译页面及其数据库当前投影，用于构造有序变更动作。 */
    private record PageCandidate(KnowledgeCompiler.CompiledPage page, KnowledgeCompiler.CurrentPage currentPage) { }
    /** 自动发布页面的关系重建上下文。 */
    private record PublishedPage(UUID pageId, String content) { }
}
