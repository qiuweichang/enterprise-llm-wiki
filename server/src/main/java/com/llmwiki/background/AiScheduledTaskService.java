package com.llmwiki.background;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理面向企业知识库的 AI 定时任务。任务先调用已测试的大模型，再以 TEXT 来源和摄取任务进入
 * 既有编译、审核链路，因此模型结果不会绕过来源追溯和审核规则。
 */
@Service
public class AiScheduledTaskService {
    private static final Logger log = LoggerFactory.getLogger(AiScheduledTaskService.class);
    private final JdbcClient jdbc;
    private final TenantDatabaseContext tenantDatabaseContext;
    private final ModelSettingsService modelSettingsService;
    private final OpenAiCompatibleClient modelClient;
    private final AiVendorNewsCollector vendorNewsCollector;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    /** 显式短事务用于虚拟线程中的状态回写；类内调用不会经过 Spring 的 @Transactional 代理。 */
    private final TransactionTemplate transactionTemplate;

    /** 创建 AI 定时任务服务。 */
    public AiScheduledTaskService(JdbcClient jdbc, TenantDatabaseContext tenantDatabaseContext,
                                  ModelSettingsService modelSettingsService, OpenAiCompatibleClient modelClient,
                                  AiVendorNewsCollector vendorNewsCollector, AuditService auditService,
                                  ObjectMapper objectMapper, TransactionTemplate transactionTemplate) {
        this.jdbc = jdbc;
        this.tenantDatabaseContext = tenantDatabaseContext;
        this.modelSettingsService = modelSettingsService;
        this.modelClient = modelClient;
        this.vendorNewsCollector = vendorNewsCollector;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /** 查询当前空间任务定义。 */
    @Transactional(readOnly = true)
    public List<TaskView> listTasks() {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        return queryTasks(user.organizationId(), user.workspaceId(), 100);
    }

    /** 创建一个任务；启用任务前校验当前模型可调用。 */
    @Transactional
    public TaskView create(TaskRequest request) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        ValidSchedule schedule = validate(request);
        if (request.enabled()) modelSettingsService.requireRunnable(user.organizationId(), user.workspaceId());
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into ai_scheduled_tasks(id, organization_id, workspace_id, name, prompt, task_type,
                    collection_window_days, frequency, run_time, day_of_week, interval_minutes, enabled,
                    next_run_at, created_by, updated_by)
                values (:id, :organizationId, :workspaceId, :name, :prompt, :taskType,
                    :collectionWindowDays, :frequency, :runTime, :dayOfWeek, :intervalMinutes, :enabled,
                    :nextRunAt, :userId, :userId)
                """).param("id", id).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("name", required(request.name(), "任务名称不能为空")).param("prompt", required(request.prompt(), "查询指令不能为空"))
                .param("taskType", normalizeTaskType(request.taskType()))
                .param("collectionWindowDays", normalizeWindowDays(request.collectionWindowDays()))
                .param("frequency", schedule.frequency()).param("runTime", schedule.runTime())
                .param("dayOfWeek", schedule.dayOfWeek()).param("intervalMinutes", schedule.intervalMinutes())
                .param("enabled", request.enabled()).param("nextRunAt", Timestamp.from(schedule.nextRunAt()))
                .param("userId", user.userId()).update();
        auditService.record("AI_TASK_CREATED", "AI_TASK", id, null,
                Map.of("name", request.name(), "frequency", schedule.frequency()), Map.of("enabled", request.enabled()));
        return findTask(id, user);
    }

    /** 更新任务定义；变更频率后从当前时间重新计算下一次执行时间。 */
    @Transactional
    public TaskView update(UUID taskId, TaskRequest request) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        ValidSchedule schedule = validate(request);
        if (request.enabled()) modelSettingsService.requireRunnable(user.organizationId(), user.workspaceId());
        int updated = jdbc.sql("""
                update ai_scheduled_tasks set name = :name, prompt = :prompt, task_type = :taskType,
                    collection_window_days = :collectionWindowDays, frequency = :frequency,
                    run_time = :runTime, day_of_week = :dayOfWeek, interval_minutes = :intervalMinutes,
                    enabled = :enabled, next_run_at = :nextRunAt, updated_by = :userId, updated_at = now()
                where id = :id and organization_id = :organizationId and workspace_id = :workspaceId
                """).param("id", taskId).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("name", required(request.name(), "任务名称不能为空")).param("prompt", required(request.prompt(), "查询指令不能为空"))
                .param("taskType", normalizeTaskType(request.taskType()))
                .param("collectionWindowDays", normalizeWindowDays(request.collectionWindowDays()))
                .param("frequency", schedule.frequency()).param("runTime", schedule.runTime())
                .param("dayOfWeek", schedule.dayOfWeek()).param("intervalMinutes", schedule.intervalMinutes())
                .param("enabled", request.enabled()).param("nextRunAt", Timestamp.from(schedule.nextRunAt())).param("userId", user.userId()).update();
        if (updated == 0) throw new ApiException(HttpStatus.NOT_FOUND, "AI_TASK_NOT_FOUND", "定时任务不存在");
        auditService.record("AI_TASK_UPDATED", "AI_TASK", taskId, null,
                Map.of("name", request.name(), "frequency", schedule.frequency()), Map.of("enabled", request.enabled()));
        return findTask(taskId, user);
    }

    /** 删除一个不再需要的任务及其执行记录，删除动作写入审计日志。 */
    @Transactional
    public void delete(UUID taskId) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        int deleted = jdbc.sql("delete from ai_scheduled_tasks where id = :id and organization_id = :organizationId and workspace_id = :workspaceId")
                .param("id", taskId).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId()).update();
        if (deleted == 0) throw new ApiException(HttpStatus.NOT_FOUND, "AI_TASK_NOT_FOUND", "定时任务不存在");
        auditService.record("AI_TASK_DELETED", "AI_TASK", taskId, null, Map.of(), Map.of());
    }

    /** 立即排队执行一个任务，实际联网在后台租约线程中完成。 */
    @Transactional
    public UUID trigger(UUID taskId) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        modelSettingsService.requireRunnable(user.organizationId(), user.workspaceId());
        UUID insertedRunId = jdbc.sql("""
                insert into ai_scheduled_task_runs(organization_id, workspace_id, task_id, trigger_type)
                select organization_id, workspace_id, id, 'MANUAL' from ai_scheduled_tasks
                where id = :id and organization_id = :organizationId and workspace_id = :workspaceId and enabled
                on conflict do nothing returning id
                """).param("id", taskId).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .query(UUID.class).optional().orElse(null);
        UUID runId = jdbc.sql("""
                select id from ai_scheduled_task_runs where task_id = :taskId and status in ('PENDING', 'RUNNING')
                order by created_at desc limit 1
                """).param("taskId", taskId).query(UUID.class).optional().orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "AI_TASK_NOT_FOUND", "任务不存在、已停用或已有运行记录"));
        if (insertedRunId != null) auditService.record("AI_TASK_TRIGGERED", "AI_TASK_RUN", runId, null,
                Map.of("taskId", taskId, "triggerType", "MANUAL"), Map.of());
        return runId;
    }

    /** 查询任务执行记录，供前端展示每次状态和结果。 */
    @Transactional(readOnly = true)
    public List<RunView> listRuns(int limit) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        return jdbc.sql("""
                select r.id, r.task_id, t.name as task_name, r.status, r.trigger_type, r.model_provider,
                       r.model_name, r.generated_source_id, r.ingestion_job_id, r.collection_details, r.result_summary,
                       r.error_message, r.started_at, r.finished_at, r.created_at
                from ai_scheduled_task_runs r join ai_scheduled_tasks t on t.id = r.task_id
                where r.organization_id = :organizationId and r.workspace_id = :workspaceId
                order by r.created_at desc limit :limit
                """).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("limit", Math.max(1, Math.min(limit, 200))).query((rs, rowNum) -> new RunView(
                        (UUID) rs.getObject("id"), (UUID) rs.getObject("task_id"), rs.getString("task_name"),
                        rs.getString("status"), rs.getString("trigger_type"), rs.getString("model_provider"),
                        rs.getString("model_name"), (UUID) rs.getObject("generated_source_id"),
                        (UUID) rs.getObject("ingestion_job_id"), parseDetails(rs.getString("collection_details")),
                        rs.getString("result_summary"), rs.getString("error_message"),
                        nullableInstant(rs.getTimestamp("started_at")), nullableInstant(rs.getTimestamp("finished_at")),
                        rs.getTimestamp("created_at").toInstant())).list();
    }

    /** 扫描并排队到期任务；数据库行锁保证多实例不会重复排队同一任务。 */
    @Transactional
    public void enqueueDueTasks() {
        enableSchedulerMode();
        List<DueTask> due = jdbc.sql("""
                select t.id, t.organization_id, t.workspace_id, t.frequency, t.run_time, t.day_of_week,
                       t.interval_minutes, t.next_run_at
                from ai_scheduled_tasks t
                where t.enabled and t.next_run_at <= now()
                  and not exists (select 1 from ai_scheduled_task_runs r where r.task_id = t.id and r.status in ('PENDING','RUNNING'))
                order by t.next_run_at limit 20 for update of t skip locked
                """).query((rs, rowNum) -> new DueTask((UUID) rs.getObject("id"), (UUID) rs.getObject("organization_id"),
                        (UUID) rs.getObject("workspace_id"), rs.getString("frequency"), rs.getTime("run_time").toLocalTime(),
                        (Integer) rs.getObject("day_of_week"), (Integer) rs.getObject("interval_minutes"),
                        rs.getTimestamp("next_run_at").toInstant())).list();
        for (DueTask task : due) {
            jdbc.sql("insert into ai_scheduled_task_runs(organization_id, workspace_id, task_id, trigger_type) values (:organizationId, :workspaceId, :taskId, 'SCHEDULED') on conflict do nothing")
                    .param("organizationId", task.organizationId()).param("workspaceId", task.workspaceId()).param("taskId", task.id()).update();
            jdbc.sql("update ai_scheduled_tasks set next_run_at = :nextRunAt, last_run_at = now(), updated_at = now() where id = :id")
                    .param("nextRunAt", Timestamp.from(nextRun(task.frequency(), task.runTime(), task.dayOfWeek(), task.intervalMinutes(), Instant.now())))
                    .param("id", task.id()).update();
            log.info("AI scheduled task enqueued taskId={} workspaceId={} nextRunAt={}", task.id(), task.workspaceId(), task.nextRunAt());
        }
    }

    /** 领取一个待执行任务并写入租约，防止模型调用期间持有数据库行锁。 */
    @Transactional
    public RunLease claim(String owner, long leaseSeconds) {
        enableSchedulerMode();
        return jdbc.sql("""
                with candidate as (select r.id from ai_scheduled_task_runs r
                    where r.status = 'PENDING' or (r.status = 'RUNNING' and r.lease_until < now())
                    order by r.created_at limit 1 for update skip locked)
                update ai_scheduled_task_runs r set status = 'RUNNING', lease_owner = :owner,
                    lease_until = now() + cast(:leaseSeconds || ' seconds' as interval),
                    started_at = coalesce(r.started_at, now()), error_message = null
                from candidate c, ai_scheduled_tasks t
                where r.id = c.id and t.id = r.task_id and t.enabled
                returning r.id, r.organization_id, r.workspace_id, r.task_id, t.name, t.prompt,
                          t.task_type, t.collection_window_days, t.created_by, r.trigger_type
                """).param("owner", owner).param("leaseSeconds", Long.toString(leaseSeconds))
                .query((rs, rowNum) -> new RunLease((UUID) rs.getObject("id"), (UUID) rs.getObject("organization_id"),
                        (UUID) rs.getObject("workspace_id"), (UUID) rs.getObject("task_id"), rs.getString("name"),
                        rs.getString("prompt"), rs.getString("task_type"), rs.getInt("collection_window_days"),
                        (UUID) rs.getObject("created_by"), rs.getString("trigger_type"))).optional().orElse(null);
    }

    /** 在事务外调用模型并将结果作为 TEXT 来源交给原有摄取编译链。 */
    public void process(RunLease lease, String owner) {
        try {
            AiVendorNewsCollector.CollectionBundle bundle = null;
            String userPrompt = lease.prompt();
            if ("AI_VENDOR_NEWS".equals(lease.taskType())) {
                LocalDate toDate = LocalDate.now(ZoneId.systemDefault());
                LocalDate fromDate = toDate.minusDays(lease.collectionWindowDays() - 1L);
                bundle = vendorNewsCollector.collect(fromDate, toDate);
                userPrompt = buildVendorNewsPrompt(lease.prompt(), bundle);
            }
            OpenAiCompatibleClient.Completion completion = modelClient.completeText(lease.organizationId(), lease.workspaceId(),
                    "你是企业知识库的定时资料研究员。只能依据提示词中附带的官方网页证据输出事实性 Markdown；没有官方证据的内容必须省略。不要输出寒暄或代码围栏。",
                    userPrompt);
            if (completion == null) {
                transactionTemplate.executeWithoutResult(status -> finishSkipped(
                        lease, owner, "当前空间没有可调用的大模型，任务未生成知识来源。"));
                return;
            }
            AiVendorNewsCollector.CollectionBundle collected = bundle;
            GeneratedSource source = transactionTemplate.execute(status ->
                    createGeneratedSource(lease, completion, collected));
            transactionTemplate.executeWithoutResult(status ->
                    finishSucceeded(lease, owner, completion, source, collected));
            log.info("AI scheduled task completed taskId={} runId={} sourceId={} ingestionJobId={}", lease.taskId(), lease.runId(), source.sourceId(), source.jobId());
        } catch (Exception exception) {
            try {
                transactionTemplate.executeWithoutResult(status -> finishFailed(lease, owner, exception));
            } catch (Exception persistenceException) {
                log.error("AI scheduled task failure status could not be persisted taskId={} runId={}",
                        lease.taskId(), lease.runId(), persistenceException);
            }
            log.error("AI scheduled task failed taskId={} runId={}", lease.taskId(), lease.runId(), exception);
        }
    }

    /** 在同一事务内登记 AI 来源、来源版本和摄取任务，保证结果可追溯。 */
    @Transactional
    protected GeneratedSource createGeneratedSource(RunLease lease, OpenAiCompatibleClient.Completion completion,
                                                      AiVendorNewsCollector.CollectionBundle bundle) {
        tenantDatabaseContext.apply(lease.organizationId(), lease.workspaceId());
        String title = lease.name() + " · " + LocalDate.now(ZoneId.systemDefault());
        String content = completion.content().trim();
        UUID sourceId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String hash = ContentHash.sha256(content);
        // 同一天允许不同回溯窗口各自产生可追溯来源；完全相同的窗口仍保持幂等，避免重复编译。
        String canonicalUri = bundle == null ? null
                : "ai-vendor-news://" + bundle.fromDate() + "/" + bundle.toDate();
        if (canonicalUri != null) {
            UUID existingSourceId = jdbc.sql("select id from sources where canonical_uri = :uri and status <> 'ARCHIVED'")
                    .param("uri", canonicalUri).query(UUID.class).optional().orElse(null);
            if (existingSourceId != null) {
                // 同日重跑也必须形成新的来源版本和摄取任务，否则“手动重试”只会返回旧任务而不处理新证据。
                int nextVersion = jdbc.sql("select coalesce(max(version_no), 0) + 1 from source_versions where source_id = :sourceId")
                        .param("sourceId", existingSourceId).query(Integer.class).single();
                jdbc.sql("insert into source_versions(id, organization_id, workspace_id, source_id, version_no, extracted_markdown, content_hash, extraction_metadata, created_by) values (:id, :organizationId, :workspaceId, :sourceId, :versionNo, :content, :hash, cast(:metadata as jsonb), :userId)")
                        .param("id", versionId).param("organizationId", lease.organizationId())
                        .param("workspaceId", lease.workspaceId()).param("sourceId", existingSourceId)
                        .param("versionNo", nextVersion).param("content", content).param("hash", hash)
                        .param("metadata", json(sourceMetadata(completion, bundle))).param("userId", lease.createdBy()).update();
                jdbc.sql("update sources set latest_version_id = :versionId, content_hash = :hash, status = 'PENDING', updated_at = now(), version = version + 1 where id = :sourceId")
                        .param("versionId", versionId).param("hash", hash).param("sourceId", existingSourceId).update();
                jdbc.sql("insert into ingestion_jobs(id, organization_id, workspace_id, source_id, requested_by, payload) values (:id, :organizationId, :workspaceId, :sourceId, :userId, cast(:payload as jsonb))")
                        .param("id", jobId).param("organizationId", lease.organizationId())
                        .param("workspaceId", lease.workspaceId()).param("sourceId", existingSourceId)
                        .param("userId", lease.createdBy())
                        .param("payload", json(Map.of("reuseLatest", true, "aiTaskRunId", lease.runId()))).update();
                auditService.record(lease.organizationId(), lease.workspaceId(), lease.createdBy(),
                        "AI_TASK_SOURCE_REFRESHED", "SOURCE", existingSourceId, null,
                        Map.of("taskId", lease.taskId(), "runId", lease.runId(), "jobId", jobId, "versionNo", nextVersion),
                        Map.of("provider", completion.provider(), "model", completion.modelName()));
                log.info("AI vendor news source refreshed canonicalUri={} sourceId={} versionNo={} jobId={}",
                        canonicalUri, existingSourceId, nextVersion, jobId);
                return new GeneratedSource(existingSourceId, jobId);
            }
        }
        jdbc.sql("insert into sources(id, organization_id, workspace_id, source_type, title, canonical_uri, status, content_hash, created_by) values (:id, :organizationId, :workspaceId, 'TEXT', :title, :canonicalUri, 'PENDING', :hash, :userId)")
                .param("id", sourceId).param("organizationId", lease.organizationId()).param("workspaceId", lease.workspaceId())
                .param("title", title).param("canonicalUri", canonicalUri).param("hash", hash).param("userId", lease.createdBy()).update();
        jdbc.sql("insert into source_versions(id, organization_id, workspace_id, source_id, version_no, extracted_markdown, content_hash, extraction_metadata, created_by) values (:id, :organizationId, :workspaceId, :sourceId, 1, :content, :hash, cast(:metadata as jsonb), :userId)")
                .param("id", versionId).param("organizationId", lease.organizationId()).param("workspaceId", lease.workspaceId()).param("sourceId", sourceId)
                .param("content", content).param("hash", hash).param("metadata", json(sourceMetadata(completion, bundle)))
                .param("userId", lease.createdBy()).update();
        jdbc.sql("update sources set latest_version_id = :versionId where id = :sourceId").param("versionId", versionId).param("sourceId", sourceId).update();
        jdbc.sql("insert into ingestion_jobs(id, organization_id, workspace_id, source_id, requested_by, payload) values (:id, :organizationId, :workspaceId, :sourceId, :userId, cast(:payload as jsonb))")
                .param("id", jobId).param("organizationId", lease.organizationId()).param("workspaceId", lease.workspaceId()).param("sourceId", sourceId)
                .param("userId", lease.createdBy()).param("payload", json(Map.of("reuseLatest", true, "aiTaskRunId", lease.runId()))).update();
        auditService.record(lease.organizationId(), lease.workspaceId(), lease.createdBy(), "AI_TASK_SOURCE_CREATED", "SOURCE", sourceId, null,
                Map.of("taskId", lease.taskId(), "runId", lease.runId(), "jobId", jobId), Map.of("provider", completion.provider(), "model", completion.modelName()));
        return new GeneratedSource(sourceId, jobId);
    }

    /** 持久化成功结果，状态成功表示模型结果已经进入摄取队列。 */
    @Transactional
    protected void finishSucceeded(RunLease lease, String owner, OpenAiCompatibleClient.Completion completion,
                                   GeneratedSource source, AiVendorNewsCollector.CollectionBundle bundle) {
        tenantDatabaseContext.apply(lease.organizationId(), lease.workspaceId());
        jdbc.sql("update ai_scheduled_task_runs set status = 'SUCCEEDED', lease_owner = null, lease_until = null, model_provider = :provider, model_name = :modelName, generated_source_id = :sourceId, ingestion_job_id = :jobId, collection_details = cast(:details as jsonb), result_summary = :summary, finished_at = now() where id = :id and lease_owner = :owner")
                .param("provider", completion.provider()).param("modelName", completion.modelName()).param("sourceId", source.sourceId()).param("jobId", source.jobId())
                .param("details", json(collectionDetails(bundle)))
                .param("summary", bundle == null ? "大模型已返回内容，已生成资料来源并进入知识编译队列，已有知识更新仍需审核。"
                        : "已采集 " + bundle.pages().size() + " 个厂商官方页面，生成增量来源并进入知识编译队列；失败 " + bundle.failures().size() + " 个。")
                .param("id", lease.runId()).param("owner", owner).update();
    }

    /** 持久化模型不可用的跳过结果。 */
    @Transactional
    protected void finishSkipped(RunLease lease, String owner, String message) {
        tenantDatabaseContext.apply(lease.organizationId(), lease.workspaceId());
        jdbc.sql("update ai_scheduled_task_runs set status = 'SKIPPED', lease_owner = null, lease_until = null, result_summary = :summary, finished_at = now() where id = :id and lease_owner = :owner")
                .param("summary", message).param("id", lease.runId()).param("owner", owner).update();
    }

    /** 持久化失败结果，保留可展示的模型额度或网络错误。 */
    @Transactional
    protected void finishFailed(RunLease lease, String owner, Exception exception) {
        tenantDatabaseContext.apply(lease.organizationId(), lease.workspaceId());
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        if (message.length() > 2000) message = message.substring(0, 2000);
        jdbc.sql("update ai_scheduled_task_runs set status = 'FAILED', lease_owner = null, lease_until = null, error_message = :error, finished_at = now() where id = :id and lease_owner = :owner")
                .param("error", message).param("id", lease.runId()).param("owner", owner).update();
    }

    /** 根据频率计算下一次触发时间，统一使用服务器时区保存为绝对时间。 */
    private Instant nextRun(String frequency, LocalTime runTime, Integer dayOfWeek, Integer intervalMinutes, Instant from) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime base = LocalDateTime.ofInstant(from, zone);
        if ("INTERVAL".equals(frequency)) return from.plusSeconds(intervalMinutes * 60L);
        LocalDate date = base.toLocalDate();
        if ("WEEKLY".equals(frequency)) {
            date = date.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(dayOfWeek)));
            LocalDateTime candidate = LocalDateTime.of(date, runTime);
            if (!candidate.isAfter(base)) candidate = candidate.plusWeeks(1);
            return candidate.atZone(zone).toInstant();
        }
        LocalDateTime candidate = LocalDateTime.of(date, runTime);
        if (!candidate.isAfter(base)) candidate = candidate.plusDays(1);
        return candidate.atZone(zone).toInstant();
    }

    /** 校验任务请求并计算首次执行时间。 */
    private ValidSchedule validate(TaskRequest request) {
        String frequency = request.frequency() == null ? "DAILY" : request.frequency().toUpperCase();
        if (!List.of("DAILY", "WEEKLY", "INTERVAL").contains(frequency)) throw new ApiException(HttpStatus.BAD_REQUEST, "AI_TASK_FREQUENCY_INVALID", "频率必须是每天、每周或按间隔");
        LocalTime runTime;
        try { runTime = LocalTime.parse(request.runTime() == null || request.runTime().isBlank() ? "12:00" : request.runTime()); }
        catch (Exception exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "AI_TASK_TIME_INVALID", "执行时间格式应为 HH:mm"); }
        Integer day = request.dayOfWeek();
        Integer interval = request.intervalMinutes();
        if ("WEEKLY".equals(frequency) && (day == null || day < 1 || day > 7)) throw new ApiException(HttpStatus.BAD_REQUEST, "AI_TASK_DAY_INVALID", "每周任务必须选择 1-7 的星期");
        if ("INTERVAL".equals(frequency) && (interval == null || interval < 30 || interval > 10080)) throw new ApiException(HttpStatus.BAD_REQUEST, "AI_TASK_INTERVAL_INVALID", "间隔必须在 30 分钟到 7 天之间");
        return new ValidSchedule(frequency, runTime, "WEEKLY".equals(frequency) ? day : null, "INTERVAL".equals(frequency) ? interval : null,
                nextRun(frequency, runTime, day, interval, Instant.now()));
    }

    /** 构造带时间边界和官方证据的资讯整理提示，禁止模型补写证据之外的“最新消息”。 */
    private String buildVendorNewsPrompt(String taskPrompt, AiVendorNewsCollector.CollectionBundle bundle) {
        return """
                %s

                只整理发布日期位于 %s 至 %s（含首尾）之间的厂商、模型版本或官方产品消息。
                输出结构化 Markdown：每条更新使用二级标题，正文必须包含“厂商”“发布日期”“关联模型”“变化摘要”“官方来源”。
                官方来源必须原样使用证据包中的 URL。发布日期不明确、超出时间窗口或只有第三方转述的内容全部省略。
                厂商统一使用 OpenAI、Anthropic、Google DeepMind、xAI、字节跳动 Seed、Moonshot AI、MiniMax 稀宇科技、智谱 Z.ai、DeepSeek、阿里通义千问、Meta Llama。

                以下是本次实时抓取的官方证据：
                %s
                """.formatted(taskPrompt, bundle.fromDate(), bundle.toDate(), bundle.evidenceMarkdown());
    }

    /** 生成来源版本的采集元数据，供证据追溯和问题排查。 */
    private Map<String, Object> sourceMetadata(OpenAiCompatibleClient.Completion completion,
                                               AiVendorNewsCollector.CollectionBundle bundle) {
        if (bundle == null) {
            return Map.of("extractor", "ai-scheduled-task", "provider", completion.provider(),
                    "model", completion.modelName());
        }
        return Map.of("extractor", "official-ai-vendor-feeds", "provider", completion.provider(),
                "model", completion.modelName(), "fromDate", bundle.fromDate().toString(),
                "toDate", bundle.toDate().toString(), "officialUrls",
                bundle.pages().stream().map(AiVendorNewsCollector.CapturedPage::url).toList());
    }

    /** 将采集统计保存到任务运行记录，前端无需解析模型正文即可展示真实处理结果。 */
    private Map<String, Object> collectionDetails(AiVendorNewsCollector.CollectionBundle bundle) {
        if (bundle == null) return Map.of();
        return Map.of("fromDate", bundle.fromDate().toString(), "toDate", bundle.toDate().toString(),
                "captured", bundle.pages().size(), "failed", bundle.failures().size(),
                "failures", bundle.failures(), "urls",
                bundle.pages().stream().map(AiVendorNewsCollector.CapturedPage::url).toList());
    }

    /** 规范任务类型，未知值直接拒绝，避免任务悄悄退化成无联网能力的通用提示词。 */
    private String normalizeTaskType(String value) {
        String type = value == null || value.isBlank() ? "GENERIC" : value.trim().toUpperCase();
        if (!List.of("GENERIC", "AI_VENDOR_NEWS").contains(type)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AI_TASK_TYPE_INVALID", "不支持的 AI 定时任务类型");
        }
        return type;
    }

    /** 规范资讯回溯天数，默认 31 天以覆盖“最近一个月”的自然日边界。 */
    private int normalizeWindowDays(Integer value) {
        int days = value == null ? 31 : value;
        if (days < 1 || days > 365) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AI_TASK_WINDOW_INVALID", "资讯回溯天数必须在 1 到 365 之间");
        }
        return days;
    }

    private String required(String value, String message) { if (value == null || value.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "AI_TASK_FIELD_REQUIRED", message); return value.trim(); }
    private TaskView findTask(UUID id, AuthenticatedUser user) { return queryTasks(user.organizationId(), user.workspaceId(), 100).stream().filter(task -> task.id().equals(id)).findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "AI_TASK_NOT_FOUND", "定时任务不存在")); }
    private List<TaskView> queryTasks(UUID organizationId, UUID workspaceId, int limit) { return jdbc.sql("select id, name, prompt, task_type, collection_window_days, frequency, to_char(run_time, 'HH24:MI') as run_time, day_of_week, interval_minutes, enabled, next_run_at, last_run_at, created_at from ai_scheduled_tasks where organization_id = :organizationId and workspace_id = :workspaceId order by created_at desc limit :limit").param("organizationId", organizationId).param("workspaceId", workspaceId).param("limit", limit).query((rs, rowNum) -> new TaskView((UUID) rs.getObject("id"), rs.getString("name"), rs.getString("prompt"), rs.getString("task_type"), rs.getInt("collection_window_days"), rs.getString("frequency"), rs.getString("run_time"), (Integer) rs.getObject("day_of_week"), (Integer) rs.getObject("interval_minutes"), rs.getBoolean("enabled"), rs.getTimestamp("next_run_at").toInstant(), nullableInstant(rs.getTimestamp("last_run_at")), rs.getTimestamp("created_at").toInstant())).list(); }
    private Instant nullableInstant(java.sql.Timestamp value) { return value == null ? null : value.toInstant(); }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (JsonProcessingException exception) { throw new IllegalArgumentException("Unable to serialize AI task payload", exception); } }
    /** 将运行记录 JSON 转换为前端可直接消费的结构，历史空值安全降级为空对象。 */
    private Map<String, Object> parseDetails(String value) { try { return value == null || value.isBlank() ? Map.of() : objectMapper.readValue(value, new TypeReference<>() { }); } catch (JsonProcessingException exception) { log.error("Unable to parse AI task collection details", exception); return Map.of(); } }

    /**
     * 为后台短事务开启跨租户调度标记；该标记只在当前数据库事务有效，模型调用前会切回具体租户上下文。
     * 任务表策略同时要求该标记，避免普通用户请求通过 RLS 看到其他租户的任务。
     */
    private void enableSchedulerMode() {
        jdbc.sql("select set_config('app.scheduler_mode', 'true', true)").query().singleRow();
    }

    /** 前端创建或更新任务请求。 */
    public record TaskRequest(String name, String prompt, String taskType, Integer collectionWindowDays,
                              String frequency, String runTime, Integer dayOfWeek, Integer intervalMinutes,
                              boolean enabled) { }
    /** 前端任务定义视图。 */
    public record TaskView(UUID id, String name, String prompt, String taskType, int collectionWindowDays,
                           String frequency, String runTime, Integer dayOfWeek, Integer intervalMinutes,
                           boolean enabled, Instant nextRunAt, Instant lastRunAt, Instant createdAt) { }
    /** 前端任务执行记录视图。 */
    public record RunView(UUID id, UUID taskId, String taskName, String status, String triggerType,
                          String modelProvider, String modelName, UUID generatedSourceId, UUID ingestionJobId,
                          Map<String, Object> collectionDetails, String resultSummary, String errorMessage,
                          Instant startedAt, Instant finishedAt, Instant createdAt) { }
    /** 后台领取任务时携带的不可变提示词和租户信息。 */
    public record RunLease(UUID runId, UUID organizationId, UUID workspaceId, UUID taskId, String name,
                           String prompt, String taskType, int collectionWindowDays, UUID createdBy,
                           String triggerType) { }
    private record DueTask(UUID id, UUID organizationId, UUID workspaceId, String frequency, LocalTime runTime, Integer dayOfWeek, Integer intervalMinutes, Instant nextRunAt) { }
    private record ValidSchedule(String frequency, LocalTime runTime, Integer dayOfWeek, Integer intervalMinutes, Instant nextRunAt) { }
    private record GeneratedSource(UUID sourceId, UUID jobId) { }
}
