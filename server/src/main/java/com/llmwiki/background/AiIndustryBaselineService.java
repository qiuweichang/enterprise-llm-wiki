package com.llmwiki.background;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmwiki.audit.AuditService;
import com.llmwiki.common.ApiException;
import com.llmwiki.common.ContentHash;
import com.llmwiki.common.Slugifier;
import com.llmwiki.security.AuthenticatedUser;
import com.llmwiki.security.RequestContext;
import com.llmwiki.security.TenantDatabaseContext;
import com.llmwiki.source.LocalObjectStorage;
import com.llmwiki.wiki.WikiRelationIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 事务化重建当前空间的 AI 行业基线知识。
 * 基线采用人工核验的官方来源和显式 WikiLink，不经过通用章节拆分，因此厂商、模型和发布事件不会混成同一层。
 */
@Service
public class AiIndustryBaselineService {
    private static final Logger log = LoggerFactory.getLogger(AiIndustryBaselineService.class);
    /** 防止误点清库的固定确认文本。 */
    private static final String CONFIRMATION = "重建AI行业知识库";
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final TenantDatabaseContext tenantDatabaseContext;
    private final WikiRelationIndexer relationIndexer;
    private final AuditService auditService;
    private final LocalObjectStorage objectStorage;

    /** 创建 AI 行业基线服务。 */
    public AiIndustryBaselineService(JdbcClient jdbc, ObjectMapper objectMapper,
                                     TenantDatabaseContext tenantDatabaseContext,
                                     WikiRelationIndexer relationIndexer, AuditService auditService,
                                     LocalObjectStorage objectStorage) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.tenantDatabaseContext = tenantDatabaseContext;
        this.relationIndexer = relationIndexer;
        this.auditService = auditService;
        this.objectStorage = objectStorage;
    }

    /**
     * 清空当前空间的旧知识后导入基线；数据库写入要么全部成功，要么全部回滚。
     * 原始文件目录只在数据库事务提交成功后删除，避免回滚后丢失仍被引用的文件。
     *
     * @param request 必须包含固定确认文本
     * @return 导入后的来源、页面与关系统计
     */
    @Transactional
    public ImportResult resetAndImport(ResetRequest request) {
        if (request == null || !CONFIRMATION.equals(request.confirmation())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BASELINE_CONFIRMATION_REQUIRED",
                    "请输入“" + CONFIRMATION + "”确认重建当前空间知识库");
        }
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        JsonNode baseline = readBaseline();
        clearKnowledge(user);

        Map<String, SourceRef> sources = new LinkedHashMap<>();
        List<PageDraft> pages = buildPages(baseline);
        Map<String, List<PageDraft>> pagesBySource = new LinkedHashMap<>();
        for (PageDraft page : pages) pagesBySource.computeIfAbsent(page.sourceUrl(), ignored -> new ArrayList<>()).add(page);
        for (Map.Entry<String, List<PageDraft>> entry : pagesBySource.entrySet()) {
            sources.put(entry.getKey(), insertSource(user, entry.getValue(), entry.getKey()));
        }
        Map<String, UUID> pageIds = new HashMap<>();
        for (PageDraft page : pages) {
            SourceRef source = sources.get(page.sourceUrl());
            UUID pageId = insertPage(user, page, source);
            pageIds.put(page.title(), pageId);
        }
        int relations = 0;
        for (PageDraft page : pages) {
            relations += relationIndexer.rebuildOutgoing(pageIds.get(page.title()), page.markdown());
        }
        auditService.record("AI_INDUSTRY_BASELINE_IMPORTED", "WORKSPACE", user.workspaceId(), null,
                Map.of("cutoffDate", baseline.path("cutoffDate").asText(), "sourceCount", sources.size(),
                        "pageCount", pages.size(), "relationCount", relations), Map.of());
        registerObjectCleanupAfterCommit(user.organizationId(), user.workspaceId());
        log.info("AI industry baseline imported organizationId={} workspaceId={} sources={} pages={} relations={} cutoffDate={}",
                user.organizationId(), user.workspaceId(), sources.size(), pages.size(), relations,
                baseline.path("cutoffDate").asText());
        return new ImportResult(baseline.path("cutoffDate").asText(), sources.size(), pages.size(), relations);
    }

    /** 删除当前空间的知识域数据，同时保留账号、组织、权限、模型和任务配置。 */
    private void clearKnowledge(AuthenticatedUser user) {
        Map<String, Object> tenant = Map.of("organizationId", user.organizationId(), "workspaceId", user.workspaceId());
        jdbc.sql("update ai_scheduled_task_runs set generated_source_id = null, ingestion_job_id = null where organization_id = :organizationId and workspace_id = :workspaceId").params(tenant).update();
        jdbc.sql("delete from query_messages where organization_id = :organizationId and workspace_id = :workspaceId").params(tenant).update();
        jdbc.sql("delete from query_conversations where organization_id = :organizationId and workspace_id = :workspaceId").params(tenant).update();
        jdbc.sql("delete from query_runs where organization_id = :organizationId and workspace_id = :workspaceId").params(tenant).update();
        jdbc.sql("delete from wiki_relations where organization_id = :organizationId and workspace_id = :workspaceId").params(tenant).update();
        jdbc.sql("delete from evidence where organization_id = :organizationId and workspace_id = :workspaceId").params(tenant).update();
        jdbc.sql("delete from reviews where organization_id = :organizationId and workspace_id = :workspaceId").params(tenant).update();
        jdbc.sql("delete from change_set_actions where organization_id = :organizationId and workspace_id = :workspaceId").params(tenant).update();
        jdbc.sql("update wiki_pages set current_revision_id = null where organization_id = :organizationId and workspace_id = :workspaceId").params(tenant).update();
        jdbc.sql("delete from wiki_page_revisions where organization_id = :organizationId and workspace_id = :workspaceId").params(tenant).update();
        jdbc.sql("delete from change_sets where organization_id = :organizationId and workspace_id = :workspaceId").params(tenant).update();
        jdbc.sql("delete from wiki_pages where organization_id = :organizationId and workspace_id = :workspaceId").params(tenant).update();
        jdbc.sql("delete from ingestion_jobs where organization_id = :organizationId and workspace_id = :workspaceId").params(tenant).update();
        jdbc.sql("update sources set latest_version_id = null where organization_id = :organizationId and workspace_id = :workspaceId").params(tenant).update();
        jdbc.sql("delete from source_versions where organization_id = :organizationId and workspace_id = :workspaceId").params(tenant).update();
        jdbc.sql("delete from sources where organization_id = :organizationId and workspace_id = :workspaceId").params(tenant).update();
        jdbc.sql("delete from wiki_evolution_runs where organization_id = :organizationId and workspace_id = :workspaceId").params(tenant).update();
        log.info("Existing knowledge cleared organizationId={} workspaceId={}", user.organizationId(), user.workspaceId());
    }

    /** 从只读资源读取已经人工核验的官方基线。 */
    private JsonNode readBaseline() {
        try (var input = new ClassPathResource("ai-industry-baseline.json").getInputStream()) {
            return objectMapper.readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 AI 行业基线资源", exception);
        }
    }

    /** 把资源转换为根主题、厂商、模型和发布事件四类页面草稿。 */
    private List<PageDraft> buildPages(JsonNode baseline) {
        List<PageDraft> pages = new ArrayList<>();
        String rootTitle = baseline.path("title").asText();
        List<String> vendors = new ArrayList<>();
        for (JsonNode vendor : baseline.path("vendors")) vendors.add(vendor.path("name").asText());
        pages.add(new PageDraft(rootTitle, "SYNTHESIS", "ai-industry-baseline://" + baseline.path("cutoffDate").asText(),
                "# " + rootTitle + "\n\n" + baseline.path("summary").asText() + "\n\n## 厂商\n\n" + links(vendors)));
        for (JsonNode vendor : baseline.path("vendors")) {
            String vendorName = vendor.path("name").asText();
            List<String> models = new ArrayList<>();
            List<String> news = new ArrayList<>();
            for (JsonNode model : vendor.path("models")) models.add(model.path("name").asText());
            for (JsonNode item : vendor.path("news")) news.add(item.path("title").asText());
            String vendorBody = "# " + vendorName + "\n\n" + vendor.path("intro").asText()
                    + "\n\n## 代表模型\n\n" + links(models) + "\n\n## 官方发布事件\n\n" + links(news)
                    + "\n\n## 所属领域\n\n- [[" + rootTitle + "]]\n";
            pages.add(new PageDraft(vendorName, "ENTITY", vendor.path("sourceUrl").asText(), vendorBody));
            for (JsonNode model : vendor.path("models")) {
                String modelName = model.path("name").asText();
                String modelBody = "# " + modelName + "\n\n" + model.path("intro").asText()
                        + "\n\n## 基本信息\n\n- 厂商：[[" + vendorName + "]]\n- 发布日期："
                        + model.path("releaseDate").asText() + "\n- 官方来源：" + model.path("sourceUrl").asText() + "\n";
                pages.add(new PageDraft(modelName, "ENTITY", model.path("sourceUrl").asText(), modelBody));
            }
            for (JsonNode item : vendor.path("news")) {
                String title = item.path("title").asText();
                String newsBody = "# " + title + "\n\n" + item.path("summary").asText()
                        + "\n\n## 事件信息\n\n- 厂商：[[" + vendorName + "]]\n- 关联模型：[["
                        + item.path("model").asText() + "]]\n- 发布日期：" + item.path("date").asText()
                        + "\n- 官方来源：" + item.path("sourceUrl").asText() + "\n";
                pages.add(new PageDraft(title, "TOPIC", item.path("sourceUrl").asText(), newsBody));
            }
        }
        return pages;
    }

    /** 插入一条可追溯的官方资料来源及不可变版本。 */
    private SourceRef insertSource(AuthenticatedUser user, List<PageDraft> relatedPages, String url) {
        UUID sourceId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String snapshot = "# 官方资料摘录\n\n来源：" + url + "\n\n" + relatedPages.stream()
                .map(PageDraft::markdown).reduce((left, right) -> left + "\n\n---\n\n" + right).orElse("");
        String hash = ContentHash.sha256(snapshot);
        String sourceType = url.startsWith("http://") || url.startsWith("https://") ? "URL" : "TEXT";
        jdbc.sql("insert into sources(id, organization_id, workspace_id, source_type, title, canonical_uri, status, content_hash, created_by) values (:id,:organizationId,:workspaceId,:sourceType,:title,:url,'READY',:hash,:userId)")
                .param("id", sourceId).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("sourceType", sourceType).param("title", "官方资料 · " + relatedPages.getFirst().title()).param("url", url)
                .param("hash", hash).param("userId", user.userId()).update();
        jdbc.sql("insert into source_versions(id, organization_id, workspace_id, source_id, version_no, extracted_markdown, content_hash, extraction_metadata, created_by) values (:id,:organizationId,:workspaceId,:sourceId,1,:markdown,:hash,cast(:metadata as jsonb),:userId)")
                .param("id", versionId).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("sourceId", sourceId).param("markdown", snapshot).param("hash", hash)
                .param("metadata", json(Map.of("method", "curated-official-baseline", "importedAt", Instant.now().toString(), "officialUrl", url)))
                .param("userId", user.userId()).update();
        jdbc.sql("update sources set latest_version_id = :versionId where id = :sourceId")
                .param("versionId", versionId).param("sourceId", sourceId).update();
        return new SourceRef(sourceId, versionId);
    }

    /** 插入已核验页面、首个修订及其官方来源证据。 */
    private UUID insertPage(AuthenticatedUser user, PageDraft page, SourceRef source) {
        UUID pageId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        String hash = ContentHash.sha256(page.markdown());
        jdbc.sql("insert into wiki_pages(id,organization_id,workspace_id,slug,title,page_type,revision_no,content_markdown,content_hash,created_by,updated_by) values (:id,:organizationId,:workspaceId,:slug,:title,:pageType,1,:content,:hash,:userId,:userId)")
                .param("id", pageId).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("slug", Slugifier.slugify(page.title())).param("title", page.title()).param("pageType", page.pageType())
                .param("content", page.markdown()).param("hash", hash).param("userId", user.userId()).update();
        jdbc.sql("insert into wiki_page_revisions(id,organization_id,workspace_id,page_id,revision_no,title,content_markdown,content_hash,change_summary,published_by) values (:id,:organizationId,:workspaceId,:pageId,1,:title,:content,:hash,'导入官方 AI 行业基线',:userId)")
                .param("id", revisionId).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("pageId", pageId).param("title", page.title()).param("content", page.markdown()).param("hash", hash)
                .param("userId", user.userId()).update();
        jdbc.sql("update wiki_pages set current_revision_id = :revisionId where id = :pageId")
                .param("revisionId", revisionId).param("pageId", pageId).update();
        jdbc.sql("insert into evidence(organization_id,workspace_id,page_revision_id,source_id,source_version_id,quote_text,locator) values (:organizationId,:workspaceId,:revisionId,:sourceId,:sourceVersionId,:quote,:url)")
                .param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("revisionId", revisionId).param("sourceId", source.sourceId()).param("sourceVersionId", source.versionId())
                .param("quote", page.markdown().substring(0, Math.min(500, page.markdown().length()))).param("url", page.sourceUrl()).update();
        return pageId;
    }

    /** 数据库提交成功后清理该租户历史上传对象。 */
    private void registerObjectCleanupAfterCommit(UUID organizationId, UUID workspaceId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { objectStorage.deleteWorkspaceObjects(organizationId, workspaceId); }
        });
    }

    /** 生成 WikiLink 列表。 */
    private String links(List<String> titles) {
        return titles.stream().map(title -> "- [[" + title + "]]").reduce((a, b) -> a + "\n" + b).orElse("暂无");
    }

    /** JSON 序列化数据库元数据。 */
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException("无法序列化基线元数据", exception); }
    }

    /** 清库确认请求。 */
    public record ResetRequest(String confirmation) { }
    /** 基线导入统计。 */
    public record ImportResult(String cutoffDate, int sourceCount, int pageCount, int relationCount) { }
    /** 页面导入草稿。 */
    private record PageDraft(String title, String pageType, String sourceUrl, String markdown) { }
    /** 来源与版本主键。 */
    private record SourceRef(UUID sourceId, UUID versionId) { }
}
