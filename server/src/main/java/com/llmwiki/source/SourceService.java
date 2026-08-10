package com.llmwiki.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmwiki.audit.AuditService;
import com.llmwiki.common.ApiException;
import com.llmwiki.common.ContentHash;
import com.llmwiki.security.AuthenticatedUser;
import com.llmwiki.security.RequestContext;
import com.llmwiki.security.TenantDatabaseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 管理原始来源登记、版本去重和摄取任务创建。
 * 来源元数据与任务在同一事务内写入，避免出现已登记却永远不会处理的半状态。
 */
@Service
public class SourceService {
    private static final Logger log = LoggerFactory.getLogger(SourceService.class);
    private final JdbcClient jdbc;
    private final TenantDatabaseContext tenantDatabaseContext;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** 创建来源服务。 */
    public SourceService(JdbcClient jdbc, TenantDatabaseContext tenantDatabaseContext,
                         AuditService auditService, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.tenantDatabaseContext = tenantDatabaseContext;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * 登记文本或网页来源并创建后台摄取任务。
     *
     * @param request 来源请求
     * @return 来源和任务标识
     */
    @Transactional
    public CreateSourceResult create(CreateSourceRequest request) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        String type = normalizeType(request.sourceType());
        if (!"TEXT".equals(type) && !"URL".equals(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_UPLOAD_REQUIRED", "文件、音频和视频请使用上传接口");
        }
        String title = required(request.title(), "来源标题不能为空");
        if ("TEXT".equals(type) && (request.text() == null || request.text().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SOURCE_TEXT_REQUIRED", "文本来源内容不能为空");
        }
        if ("URL".equals(type) && (request.uri() == null || !request.uri().matches("^https?://.+"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALID_SOURCE_URL_REQUIRED", "网页来源必须提供 http/https 地址");
        }
        if ("URL".equals(type)) {
            ensureUriAvailable(user, request.uri().trim());
        }
        UUID sourceId = insertSource(user, type, title, "URL".equals(type) ? request.uri().trim() : null, null);
        Map<String, Object> payload;
        if ("TEXT".equals(type)) {
            UUID versionId = insertSourceVersion(user, sourceId, 1, null, request.text().trim(),
                    ContentHash.sha256(request.text().trim()), Map.of("extractor", "direct-text"));
            jdbc.sql("update sources set latest_version_id = :versionId, content_hash = :hash where id = :sourceId")
                    .param("versionId", versionId).param("hash", ContentHash.sha256(request.text().trim()))
                    .param("sourceId", sourceId).update();
            payload = Map.of("reuseLatest", true);
        } else {
            payload = Map.of("url", request.uri().trim());
        }
        UUID jobId = insertJob(user, sourceId, payload);
        auditService.record("SOURCE_CREATED", "SOURCE", sourceId, null,
                Map.of("title", title, "sourceType", type, "jobId", jobId), Map.of());
        auditService.outbox("SOURCE", sourceId, "wiki.source.created", Map.of("jobId", jobId, "sourceType", type));
        log.info("Source created sourceId={} jobId={} type={} workspaceId={} actorId={}",
                sourceId, jobId, type, user.workspaceId(), user.userId());
        return new CreateSourceResult(sourceId, jobId, "PENDING");
    }

    /**
     * 登记已写入对象存储的文件并创建后台提取任务。
     *
     * @param title 来源标题
     * @param sourceType FILE、AUDIO 或 VIDEO
     * @param object 已保存对象
     * @return 来源和任务标识
     */
    @Transactional
    public CreateSourceResult createUploaded(String title, String sourceType, LocalObjectStorage.StoredObject object) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        String type = normalizeType(sourceType);
        if (!List.of("FILE", "AUDIO", "VIDEO").contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_UPLOAD_TYPE", "上传类型必须是 FILE、AUDIO 或 VIDEO");
        }
        UUID sourceId = insertSource(user, type, required(title, "来源标题不能为空"), null, object.contentHash());
        Map<String, Object> payload = Map.of(
                "objectKey", object.objectKey(),
                "originalFilename", object.originalFilename(),
                "contentType", object.contentType() == null ? "application/octet-stream" : object.contentType(),
                "size", object.size()
        );
        UUID jobId = insertJob(user, sourceId, payload);
        auditService.record("SOURCE_UPLOADED", "SOURCE", sourceId, null,
                Map.of("sourceType", type, "jobId", jobId, "contentHash", object.contentHash()), Map.of());
        auditService.outbox("SOURCE", sourceId, "wiki.source.created", Map.of("jobId", jobId, "sourceType", type));
        log.info("Uploaded source registered sourceId={} jobId={} type={} bytes={}", sourceId, jobId, type, object.size());
        return new CreateSourceResult(sourceId, jobId, "PENDING");
    }

    /**
     * 列出当前空间来源及最近处理状态。
     */
    @Transactional(readOnly = true)
    public List<SourceSummary> list(int limit) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        return jdbc.sql("""
                        select s.id, s.source_type, s.title, s.canonical_uri, s.status, s.content_hash,
                               s.created_at, s.updated_at, j.id as job_id, j.status as job_status,
                               j.error_message, j.payload ->> 'objectKey' as object_key,
                               j.payload ->> 'contentType' as object_content_type
                        from sources s
                        left join lateral (
                            select id, status, error_message, payload from ingestion_jobs j
                            where j.source_id = s.id order by j.created_at desc limit 1
                        ) j on true
                        where s.organization_id = :organizationId and s.workspace_id = :workspaceId
                          and s.status <> 'ARCHIVED'
                        order by s.created_at desc limit :limit
                        """)
                .param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("limit", Math.max(1, Math.min(limit, 100))).query(SourceService::mapSummary).list();
    }

    /**
     * 解析租户内上传文件的下载信息；对象键只从数据库读取并再次经过根目录约束。
     *
     * @param sourceId 来源 ID
     * @param objectStorage 本地对象存储解析器
     * @return 文件路径、下载文件名和内容类型
     */
    @Transactional(readOnly = true)
    public DownloadInfo download(UUID sourceId, LocalObjectStorage objectStorage) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        StoredDownload stored = jdbc.sql("""
                        select s.title, s.source_type, latest_job.payload ->> 'objectKey' as object_key,
                               latest_job.payload ->> 'originalFilename' as original_filename,
                               latest_job.payload ->> 'contentType' as content_type
                        from sources s left join lateral (
                            select payload from ingestion_jobs where source_id = s.id order by created_at desc limit 1
                        ) latest_job on true
                        where s.id = :sourceId and s.organization_id = :organizationId
                          and s.workspace_id = :workspaceId and s.status <> 'ARCHIVED'
                        """).param("sourceId", sourceId).param("organizationId", user.organizationId())
                .param("workspaceId", user.workspaceId()).query((rs, rowNum) -> new StoredDownload(
                        rs.getString("title"), rs.getString("source_type"), rs.getString("object_key"),
                        rs.getString("original_filename"),
                        rs.getString("content_type"))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND", "来源不存在"));
        if (!List.of("FILE", "AUDIO", "VIDEO").contains(stored.sourceType()) || stored.objectKey() == null || stored.objectKey().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SOURCE_DOWNLOAD_UNAVAILABLE", "该来源没有可下载的原始文件");
        }
        Path path = objectStorage.resolve(stored.objectKey());
        if (!java.nio.file.Files.exists(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SOURCE_FILE_NOT_FOUND", "原始文件已不存在");
        }
        String filename = ensureDownloadExtension(stored.originalFilename() == null ? path.getFileName().toString()
                : stored.originalFilename(), stored.title(), stored.contentType());
        return new DownloadInfo(path, filename, stored.contentType() == null ? "application/octet-stream" : stored.contentType());
    }

    /**
     * 为历史对象键补齐下载扩展名。早期版本可能只保存哈希对象键，浏览器无法据此判断文件类型；
     * 新上传文件仍优先使用原始文件名，只有确实没有扩展名时才根据标题或 MIME 类型推断。
     */
    private String ensureDownloadExtension(String filename, String title, String contentType) {
        String currentExtension = filename.substring(filename.lastIndexOf('.') + 1);
        if (filename.lastIndexOf('.') > 0 && currentExtension.matches("[A-Za-z0-9]{1,8}")
                && !currentExtension.matches("[0-9]+")) {
            return filename;
        }
        String titleName = title == null ? "" : title.trim();
        int titleDot = titleName.lastIndexOf('.');
        if (titleDot > 0 && titleDot < titleName.length() - 1
                && Set.of("txt", "md", "markdown", "html", "htm", "doc", "docx", "pdf", "xls", "xlsx",
                "csv", "png", "jpg", "jpeg", "tif", "tiff", "bmp", "webp", "mp3", "wav", "mp4")
                .contains(titleName.substring(titleDot + 1).toLowerCase())) {
            return filename + titleName.substring(titleDot);
        }
        String extension = switch (contentType == null ? "" : contentType.toLowerCase()) {
            case "application/pdf" -> ".pdf";
            case "application/msword" -> ".doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            case "application/vnd.ms-excel" -> ".xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
            case "text/plain" -> ".txt";
            case "text/markdown" -> ".md";
            case "text/csv" -> ".csv";
            case "audio/mpeg" -> ".mp3";
            case "audio/wav", "audio/x-wav" -> ".wav";
            case "video/mp4" -> ".mp4";
            default -> "";
        };
        return extension.isBlank() ? filename + ".bin" : filename + extension;
    }

    /**
     * 为已有来源创建重新提取任务。网页会重新抓取，文件和媒体复用不可变原始对象。
     * 新内容最终仍遵循“已有页面更新必须审核”的规则。
     *
     * @param sourceId 来源 ID
     * @return 新任务
     */
    @Transactional
    public CreateSourceResult refresh(UUID sourceId) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        RefreshSource source = jdbc.sql("""
                        select s.id, s.source_type, s.canonical_uri,
                               coalesce(sv.raw_object_key, latest_job.payload ->> 'objectKey') as raw_object_key,
                               coalesce(sv.extraction_metadata ->> 'contentType',
                                        latest_job.payload ->> 'contentType') as content_type
                        from sources s
                        left join source_versions sv on sv.id = s.latest_version_id
                        left join lateral (
                            select payload from ingestion_jobs j where j.source_id = s.id
                            order by j.created_at desc limit 1
                        ) latest_job on true
                        where s.id = :sourceId and s.organization_id = :organizationId
                          and s.workspace_id = :workspaceId and s.status <> 'ARCHIVED'
                        """).param("sourceId", sourceId).param("organizationId", user.organizationId())
                .param("workspaceId", user.workspaceId())
                .query((rs, rowNum) -> new RefreshSource((UUID) rs.getObject("id"), rs.getString("source_type"),
                        rs.getString("canonical_uri"), rs.getString("raw_object_key"), rs.getString("content_type")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND", "来源不存在"));
        Map<String, Object> payload = switch (source.sourceType()) {
            case "URL" -> Map.of("url", source.canonicalUri());
            case "TEXT" -> Map.of("reuseLatest", true);
            default -> Map.of("objectKey", source.objectKey(), "contentType",
                    source.contentType() == null ? "application/octet-stream" : source.contentType());
        };
        UUID jobId = insertJob(user, sourceId, payload);
        jdbc.sql("update sources set status = 'PENDING', updated_at = now() where id = :sourceId")
                .param("sourceId", sourceId).update();
        auditService.record("SOURCE_REFRESH_REQUESTED", "SOURCE", sourceId, null,
                Map.of("jobId", jobId), Map.of());
        auditService.outbox("SOURCE", sourceId, "wiki.source.refresh-requested", Map.of("jobId", jobId));
        log.info("Source refresh requested sourceId={} jobId={} actorId={}", sourceId, jobId, user.userId());
        return new CreateSourceResult(sourceId, jobId, "PENDING");
    }

    /** 插入来源主记录。 */
    private UUID insertSource(AuthenticatedUser user, String type, String title, String uri, String contentHash) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into sources(id, organization_id, workspace_id, source_type, title, canonical_uri,
                                            content_hash, created_by)
                        values (:id, :organizationId, :workspaceId, :sourceType, :title, :uri, :contentHash, :userId)
                        """)
                .param("id", id).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("sourceType", type).param("title", title).param("uri", uri).param("contentHash", contentHash)
                .param("userId", user.userId()).update();
        return id;
    }

    /** 插入不可变来源版本。 */
    private UUID insertSourceVersion(AuthenticatedUser user, UUID sourceId, int versionNo, String objectKey,
                                     String markdown, String hash, Map<String, ?> metadata) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into source_versions(id, organization_id, workspace_id, source_id, version_no,
                                                    raw_object_key, extracted_markdown, content_hash,
                                                    extraction_metadata, created_by)
                        values (:id, :organizationId, :workspaceId, :sourceId, :versionNo, :objectKey,
                                :markdown, :hash, cast(:metadata as jsonb), :userId)
                        """)
                .param("id", id).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("sourceId", sourceId).param("versionNo", versionNo).param("objectKey", objectKey)
                .param("markdown", markdown).param("hash", hash).param("metadata", json(metadata))
                .param("userId", user.userId()).update();
        return id;
    }

    /** 在来源事务中插入待处理任务，保证二者要么同时存在、要么都不提交。 */
    private UUID insertJob(AuthenticatedUser user, UUID sourceId, Map<String, ?> payload) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into ingestion_jobs(id, organization_id, workspace_id, source_id, requested_by, payload)
                        values (:id, :organizationId, :workspaceId, :sourceId, :userId, cast(:payload as jsonb))
                        """)
                .param("id", id).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("sourceId", sourceId).param("userId", user.userId()).param("payload", json(payload)).update();
        return id;
    }

    /** 检查租户内活动 URL 唯一性，返回明确冲突错误。 */
    private void ensureUriAvailable(AuthenticatedUser user, String uri) {
        boolean exists = jdbc.sql("""
                        select exists(select 1 from sources where organization_id = :organizationId
                            and workspace_id = :workspaceId and canonical_uri = :uri and status <> 'ARCHIVED')
                        """).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("uri", uri).query(Boolean.class).single();
        if (exists) {
            throw new ApiException(HttpStatus.CONFLICT, "SOURCE_ALREADY_EXISTS", "该网页来源已经存在");
        }
    }

    /** 规范来源类型。 */
    private String normalizeType(String sourceType) {
        String type = sourceType == null ? "TEXT" : sourceType.trim().toUpperCase();
        return switch (type) {
            case "TEXT", "URL", "FILE", "AUDIO", "VIDEO" -> type;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SOURCE_TYPE", "不支持的来源类型");
        };
    }

    /** 校验非空文本。 */
    private String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
        }
        return value.trim();
    }

    /** JSON 序列化数据库载荷。 */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize source payload", exception);
        }
    }

    /** 映射来源列表项。 */
    private static SourceSummary mapSummary(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SourceSummary((UUID) rs.getObject("id"), rs.getString("source_type"), rs.getString("title"),
                rs.getString("canonical_uri"), rs.getString("status"), rs.getString("content_hash"),
                (UUID) rs.getObject("job_id"), rs.getString("job_status"), rs.getString("error_message"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getString("object_key") != null);
    }

    /** 创建文本/URL 来源请求。 */
    public record CreateSourceRequest(String sourceType, String title, String uri, String text) { }
    /** 创建来源结果。 */
    public record CreateSourceResult(UUID sourceId, UUID jobId, String status) { }
    /** 来源列表项。 */
    public record SourceSummary(UUID id, String sourceType, String title, String canonicalUri, String status,
                                String contentHash, UUID jobId, String jobStatus, String errorMessage,
                                Instant createdAt, Instant updatedAt, boolean downloadAvailable) { }
    /** 下载响应所需的受租户约束文件信息。 */
    public record DownloadInfo(Path path, String filename, String contentType) { }
    private record StoredDownload(String title, String sourceType, String objectKey, String originalFilename,
                                  String contentType) { }
    private record RefreshSource(UUID id, String sourceType, String canonicalUri, String objectKey, String contentType) { }
}
