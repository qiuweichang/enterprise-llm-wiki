package com.llmwiki.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmwiki.common.ApiException;
import com.llmwiki.config.LlmWikiProperties;
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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 管理问 Wiki 的持久化会话、消息队列和后台回答租约，页面退出不会中断已提交问题。
 */
@Service
public class QueryConversationService {
    private static final Logger log = LoggerFactory.getLogger(QueryConversationService.class);
    /** 会话与消息的事务化数据库入口。 */
    private final JdbcClient jdbc;
    /** 为用户接口和后台完成事务启用强制 RLS。 */
    private final TenantDatabaseContext tenantDatabaseContext;
    /** 序列化和解析修订级引用。 */
    private final ObjectMapper objectMapper;
    /** 提供后台实例 ID 和租约时长。 */
    private final LlmWikiProperties properties;

    /**
     * 创建会话服务。
     *
     * @param jdbc 数据库客户端
     * @param tenantDatabaseContext 租户数据库上下文
     * @param objectMapper JSON 映射器
     * @param properties 后台配置
     */
    public QueryConversationService(JdbcClient jdbc, TenantDatabaseContext tenantDatabaseContext,
                                    ObjectMapper objectMapper, LlmWikiProperties properties) {
        this.jdbc = jdbc;
        this.tenantDatabaseContext = tenantDatabaseContext;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** 返回当前用户在当前空间的最近会话。 */
    @Transactional(readOnly = true)
    public List<ConversationSummary> list() {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        return jdbc.sql("""
                        select c.id, c.title, c.pinned, c.last_message_at, c.created_at,
                               coalesce((select m.status from query_messages m
                                   where m.conversation_id = c.id and m.role = 'ASSISTANT'
                                   order by m.sequence_no desc limit 1), 'SUCCEEDED') as status
                        from query_conversations c
                        where c.user_id = :userId
                        order by c.pinned desc, c.last_message_at desc limit 100
                        """).param("userId", user.userId()).query((rs, rowNum) -> new ConversationSummary(
                        (UUID) rs.getObject("id"), rs.getString("title"), rs.getBoolean("pinned"), rs.getString("status"),
                        rs.getTimestamp("last_message_at").toInstant(), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /**
     * 用第一个问题原子创建会话和后台消息任务。
     *
     * @param question 首个问题
     * @return 会话和待回答消息 ID
     */
    @Transactional
    public SubmitResult create(String question, boolean useModel) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        String normalized = requireQuestion(question);
        UUID conversationId = UUID.randomUUID();
        jdbc.sql("""
                        insert into query_conversations(id, organization_id, workspace_id, user_id, title)
                        values (:id, :organizationId, :workspaceId, :userId, :title)
                        """).param("id", conversationId).param("organizationId", user.organizationId())
                .param("workspaceId", user.workspaceId()).param("userId", user.userId())
                .param("title", title(normalized)).update();
        return enqueue(user, conversationId, normalized, 1, useModel);
    }

    /**
     * 向已有会话追加问题；同一会话已有回答进行中时拒绝重复提交。
     *
     * @param conversationId 会话 ID
     * @param question 新问题
     * @return 待回答消息 ID
     */
    @Transactional
    public SubmitResult submit(UUID conversationId, String question, boolean useModel) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        String normalized = requireQuestion(question);
        Integer nextSequence = jdbc.sql("""
                        select coalesce((select max(m.sequence_no) + 1 from query_messages m
                            where m.conversation_id = c.id), 1)
                        from query_conversations c
                        where c.id = :conversationId and c.user_id = :userId for update
                        """).param("conversationId", conversationId).param("userId", user.userId())
                .query(Integer.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "会话不存在"));
        boolean active = jdbc.sql("""
                        select exists(select 1 from query_messages where conversation_id = :conversationId
                            and role = 'ASSISTANT' and status in ('PENDING', 'RUNNING'))
                        """).param("conversationId", conversationId).query(Boolean.class).single();
        if (active) {
            throw new ApiException(HttpStatus.CONFLICT, "QUERY_ALREADY_RUNNING", "当前会话仍在回答，请稍后再问");
        }
        return enqueue(user, conversationId, normalized, nextSequence, useModel);
    }

    /** 返回当前用户拥有的会话及全部消息。 */
    @Transactional(readOnly = true)
    public ConversationDetail detail(UUID conversationId) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        ConversationHeader header = jdbc.sql("""
                        select id, title, created_at, updated_at from query_conversations
                        where id = :id and user_id = :userId
                        """).param("id", conversationId).param("userId", user.userId())
                .query((rs, rowNum) -> new ConversationHeader((UUID) rs.getObject("id"), rs.getString("title"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "CONVERSATION_NOT_FOUND", "会话不存在"));
        List<MessageView> messages = jdbc.sql("""
                        select id, role, status, content_markdown, citations::text as citations,
                               cache_hit, duration_ms, answer_mode, model_provider, model_name,
                               error_message, created_at, finished_at, retrieval_mode, retrieval_message
                        from query_messages where conversation_id = :conversationId order by sequence_no
                        """).param("conversationId", conversationId).query((rs, rowNum) -> new MessageView(
                        (UUID) rs.getObject("id"), rs.getString("role"), rs.getString("status"),
                        rs.getString("content_markdown"), parseCitations(rs.getString("citations")),
                        rs.getBoolean("cache_hit"), (Long) rs.getObject("duration_ms"),
                        rs.getString("answer_mode"), rs.getString("model_provider"), rs.getString("model_name"),
                        rs.getString("error_message"), rs.getTimestamp("created_at").toInstant(),
                        nullableInstant(rs.getTimestamp("finished_at")),rs.getString("retrieval_mode"),rs.getString("retrieval_message"))).list();
        return new ConversationDetail(header.id(), header.title(), header.createdAt(), header.updatedAt(), messages);
    }

    /** 修改会话标题或置顶状态，并返回更新后的列表项。 */
    @Transactional
    public ConversationSummary update(UUID conversationId, String title, Boolean pinned) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        if (title == null && pinned == null) throw new ApiException(HttpStatus.BAD_REQUEST, "CONVERSATION_PATCH_EMPTY", "没有可更新的内容");
        String normalizedTitle = title == null ? null : title.trim();
        if (normalizedTitle != null && normalizedTitle.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "CONVERSATION_TITLE_EMPTY", "会话名称不能为空");
        StringBuilder sql = new StringBuilder("update query_conversations set ");
        if (normalizedTitle != null) sql.append("title = :title, ");
        if (pinned != null) sql.append("pinned = :pinned, ");
        sql.append("updated_at = now() where id = :id and user_id = :userId");
        var statement = jdbc.sql(sql.toString()).param("id", conversationId).param("userId", user.userId());
        if (normalizedTitle != null) statement = statement.param("title", normalizedTitle);
        if (pinned != null) statement = statement.param("pinned", pinned);
        statement.update();
        return list().stream().filter(item -> item.id().equals(conversationId)).findFirst().orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "会话不存在"));
    }

    /** 删除会话，数据库级联删除其消息。 */
    @Transactional
    public void delete(UUID conversationId) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        int rows = jdbc.sql("delete from query_conversations where id = :id and user_id = :userId")
                .param("id", conversationId).param("userId", user.userId()).update();
        if (rows == 0) throw new ApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "会话不存在");
    }

    /** 原子领取一条待回答或租约过期的助手消息。 */
    @Transactional
    public QueryLease claim() {
        BaseLease base = jdbc.sql("""
                        with candidate as (
                            select id from query_messages
                            where role = 'ASSISTANT' and
                                (status = 'PENDING' or (status = 'RUNNING' and lease_until < now()))
                            order by created_at limit 1 for update skip locked
                        )
                        update query_messages m set status = 'RUNNING', lease_owner = :owner,
                            lease_until = now() + cast(:leaseSeconds || ' seconds' as interval),
                            started_at = coalesce(started_at, now()), error_message = null
                        from candidate c where m.id = c.id
                        returning m.id, m.organization_id, m.workspace_id, m.conversation_id,
                                  m.user_id, m.sequence_no, m.use_model
                        """).param("owner", properties.worker().instanceId())
                .param("leaseSeconds", Long.toString(properties.worker().leaseDuration().toSeconds()))
                .query((rs, rowNum) -> new BaseLease((UUID) rs.getObject("id"),
                        (UUID) rs.getObject("organization_id"), (UUID) rs.getObject("workspace_id"),
                        (UUID) rs.getObject("conversation_id"), (UUID) rs.getObject("user_id"),
                        rs.getInt("sequence_no"), rs.getBoolean("use_model"))).optional().orElse(null);
        if (base == null) {
            return null;
        }
        List<ContextMessage> recent = jdbc.sql("""
                        select role, content_markdown from query_messages
                        where conversation_id = :conversationId and sequence_no < :sequenceNo
                          and status = 'SUCCEEDED'
                        order by sequence_no desc limit 8
                        """).param("conversationId", base.conversationId()).param("sequenceNo", base.sequenceNo())
                .query((rs, rowNum) -> new ContextMessage(rs.getString("role"),
                        rs.getString("content_markdown"))).list();
        Collections.reverse(recent);
        String question = recent.stream().filter(message -> "USER".equals(message.role()))
                .reduce((first, second) -> second).map(ContextMessage::content)
                .orElseThrow(() -> new IllegalStateException("Query message has no user question"));
        String previousQuestion = recent.stream().filter(message -> "USER".equals(message.role()))
                .map(ContextMessage::content).filter(value -> !value.equals(question)).reduce((first, second) -> second)
                .orElse("");
        String context = recent.stream().map(message -> ("USER".equals(message.role()) ? "用户：" : "助手：") +
                limit(message.content(), 1200)).collect(java.util.stream.Collectors.joining("\n"));
        return new QueryLease(base.id(), base.organizationId(), base.workspaceId(), base.conversationId(),
                base.userId(), base.useModel(), question, previousQuestion.isBlank() ? question : previousQuestion + " " + question,
                context);
    }

    /** 将后台回答写回原消息并推进会话时间。 */
    @Transactional
    public void complete(QueryLease lease, QueryService.QueryResponse response) {
        tenantDatabaseContext.apply(lease.organizationId(), lease.workspaceId());
        int updated = jdbc.sql("""
                        update query_messages set status = 'SUCCEEDED', content_markdown = :content,
                            citations = cast(:citations as jsonb), cache_hit = :cacheHit, duration_ms = :durationMs,
                            answer_mode = :answerMode, model_provider = :modelProvider, model_name = :modelName,
                            retrieval_mode=:retrievalMode,retrieval_message=:retrievalMessage,
                            lease_owner = null, lease_until = null, finished_at = now()
                        where id = :id and lease_owner = :owner and status = 'RUNNING'
                        """).param("content", response.answerMarkdown()).param("citations", json(response.citations()))
                .param("cacheHit", response.cacheHit()).param("durationMs", response.durationMs())
                .param("answerMode", response.answerMode()).param("modelProvider", response.modelProvider())
                .param("modelName", response.modelName())
                .param("retrievalMode",response.retrievalMode()).param("retrievalMessage",response.retrievalMessage())
                .param("id", lease.messageId()).param("owner", properties.worker().instanceId()).update();
        if (updated != 1) {
            throw new IllegalStateException("Query message lease was lost before completion");
        }
        jdbc.sql("update query_conversations set last_message_at = now(), updated_at = now() where id = :id")
                .param("id", lease.conversationId()).update();
        log.info("Query conversation answer completed conversationId={} messageId={} durationMs={}",
                lease.conversationId(), lease.messageId(), response.durationMs());
    }

    /** 持久化后台回答失败，用户返回会话后仍能看到明确错误。 */
    @Transactional
    public void fail(QueryLease lease, Exception exception) {
        tenantDatabaseContext.apply(lease.organizationId(), lease.workspaceId());
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        message = limit(message, 1000);
        jdbc.sql("""
                        update query_messages set status = 'FAILED', error_message = :error,
                            lease_owner = null, lease_until = null, finished_at = now()
                        where id = :id and lease_owner = :owner
                        """).param("error", message).param("id", lease.messageId())
                .param("owner", properties.worker().instanceId()).update();
        log.error("Query conversation answer failed conversationId={} messageId={}",
                lease.conversationId(), lease.messageId(), exception);
    }

    /** 构造后台任务所需的最小用户安全上下文。 */
    public AuthenticatedUser workerUser(QueryLease lease) {
        return new AuthenticatedUser(lease.userId(), lease.organizationId(), lease.workspaceId(), null,
                "", "", 0, Set.of("QUERY_EXECUTE"));
    }

    /** 在同一事务中写入用户消息及对应待处理助手消息。 */
    private SubmitResult enqueue(AuthenticatedUser user, UUID conversationId, String question, int userSequence, boolean useModel) {
        UUID userMessageId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        jdbc.sql("""
                        insert into query_messages(id, organization_id, workspace_id, conversation_id, user_id,
                            sequence_no, role, status, content_markdown, finished_at)
                        values (:id, :organizationId, :workspaceId, :conversationId, :userId,
                                :sequenceNo, 'USER', 'SUCCEEDED', :content, now())
                        """).param("id", userMessageId).param("organizationId", user.organizationId())
                .param("workspaceId", user.workspaceId()).param("conversationId", conversationId)
                .param("userId", user.userId()).param("sequenceNo", userSequence).param("content", question).update();
        jdbc.sql("""
                        insert into query_messages(id, organization_id, workspace_id, conversation_id, user_id,
                            sequence_no, role, status, use_model)
                        values (:id, :organizationId, :workspaceId, :conversationId, :userId,
                                :sequenceNo, 'ASSISTANT', 'PENDING', :useModel)
                        """).param("id", assistantMessageId).param("organizationId", user.organizationId())
                .param("workspaceId", user.workspaceId()).param("conversationId", conversationId)
                .param("userId", user.userId()).param("sequenceNo", userSequence + 1).param("useModel", useModel).update();
        jdbc.sql("update query_conversations set last_message_at = now(), updated_at = now() where id = :id")
                .param("id", conversationId).update();
        log.info("Query conversation message enqueued conversationId={} messageId={} userId={}",
                conversationId, assistantMessageId, user.userId());
        return new SubmitResult(conversationId, assistantMessageId);
    }

    /** 校验并规范问题。 */
    private String requireQuestion(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "QUESTION_REQUIRED", "请输入问题");
        }
        String question = value.trim();
        if (question.length() > 4000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "QUESTION_TOO_LONG", "问题不能超过 4000 个字符");
        }
        return question;
    }

    /** 用首个问题生成简短会话标题。 */
    private String title(String question) {
        return question.length() <= 32 ? question : question.substring(0, 32) + "…";
    }

    /** 限制持久化上下文和错误消息长度。 */
    private String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    /** 序列化引用。 */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize conversation citations", exception);
        }
    }

    /** 解析引用。 */
    private List<QueryService.Citation> parseCitations(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to parse conversation citations", exception);
        }
    }

    /** 转换可空时间。 */
    private static Instant nullableInstant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /** 会话列表项。 */
    public record ConversationSummary(UUID id, String title, boolean pinned, String status, Instant lastMessageAt, Instant createdAt) { }
    /** 会话详情。 */
    public record ConversationDetail(UUID id, String title, Instant createdAt, Instant updatedAt,
                                     List<MessageView> messages) { }
    /** 持久化消息。 */
    public record MessageView(UUID id, String role, String status, String contentMarkdown,
                              List<QueryService.Citation> citations, boolean cacheHit, Long durationMs,
                              String answerMode, String modelProvider, String modelName, String errorMessage,
                              Instant createdAt, Instant finishedAt,String retrievalMode,String retrievalMessage) { }
    /** 提交结果。 */
    public record SubmitResult(UUID conversationId, UUID assistantMessageId) { }
    /** 后台回答租约。 */
    public record QueryLease(UUID messageId, UUID organizationId, UUID workspaceId, UUID conversationId,
                             UUID userId, boolean useModel, String question, String retrievalText, String conversationContext) { }
    /** 会话标题数据库行。 */
    private record ConversationHeader(UUID id, String title, Instant createdAt, Instant updatedAt) { }
    /** 领取后的基础消息行。 */
    private record BaseLease(UUID id, UUID organizationId, UUID workspaceId, UUID conversationId,
                             UUID userId, int sequenceNo, boolean useModel) { }
    /** 最近上下文消息。 */
    private record ContextMessage(String role, String content) { }
}
