package com.llmwiki.query;

import com.llmwiki.security.RequiresPermission;
import com.llmwiki.background.ModelSettingsService;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 暴露基于已编译 Wiki 的查询接口。
 */
@RestController
@RequestMapping("/api/query")
public class QueryController {
    private final QueryService queryService;
    private final QueryConversationService conversationService;
    private final ModelSettingsService modelSettingsService;

    /** 创建查询控制器。 */
    public QueryController(QueryService queryService, QueryConversationService conversationService,
                           ModelSettingsService modelSettingsService) {
        this.queryService = queryService;
        this.conversationService = conversationService;
        this.modelSettingsService = modelSettingsService;
    }

    /** 执行一次带引用的知识查询。 */
    @PostMapping
    @RequiresPermission("QUERY_EXECUTE")
    public QueryService.QueryResponse query(@Valid @RequestBody QueryRequest request) {
        return queryService.query(request.question(), request.useModel());
    }

    /** 列出当前用户的问 Wiki 会话。 */
    @GetMapping("/conversations")
    @RequiresPermission("QUERY_EXECUTE")
    public List<QueryConversationService.ConversationSummary> conversations() {
        return conversationService.list();
    }

    /** 返回问 Wiki 当前是否允许调用大模型及不可用的明确原因。 */
    @GetMapping("/model-status")
    @RequiresPermission("QUERY_EXECUTE")
    public ModelStatus modelStatus() {
        ModelSettingsService.ModelSettingsView model = modelSettingsService.get();
        boolean configured = model.baseUrl() != null && !model.baseUrl().isBlank()
                && model.modelName() != null && !model.modelName().isBlank();
        boolean available = model.enabled() && configured && "AVAILABLE".equals(model.credentialStatus());
        String reason = available ? null : !model.enabled() ? "后台未启用大模型"
                : !configured ? "尚未配置服务地址或模型名称"
                : model.credentialMessage() == null ? "模型连接配置不可用，请到“模型与 MCP”测试连接"
                : model.credentialMessage();
        return new ModelStatus(available, model.enabled(), model.provider(), model.modelName(), reason);
    }

    /** 用首个问题创建会话并提交后台回答。 */
    @PostMapping("/conversations")
    @RequiresPermission("QUERY_EXECUTE")
    public QueryConversationService.SubmitResult createConversation(@Valid @RequestBody QueryRequest request) {
        return conversationService.create(request.question(), request.useModel());
    }

    /** 读取一个会话的全部持久化消息。 */
    @GetMapping("/conversations/{conversationId}")
    @RequiresPermission("QUERY_EXECUTE")
    public QueryConversationService.ConversationDetail conversation(@PathVariable UUID conversationId) {
        return conversationService.detail(conversationId);
    }

    /** 向已有会话追加问题并提交后台回答。 */
    @PostMapping("/conversations/{conversationId}/messages")
    @RequiresPermission("QUERY_EXECUTE")
    public QueryConversationService.SubmitResult submitMessage(@PathVariable UUID conversationId,
                                                                @Valid @RequestBody QueryRequest request) {
        return conversationService.submit(conversationId, request.question(), request.useModel());
    }

    /** 更新当前用户会话标题或置顶状态。 */
    @PatchMapping("/conversations/{conversationId}")
    @RequiresPermission("QUERY_EXECUTE")
    public QueryConversationService.ConversationSummary updateConversation(@PathVariable UUID conversationId,
                                                                           @RequestBody ConversationPatch request) {
        return conversationService.update(conversationId, request.title(), request.pinned());
    }

    /** 删除当前用户的会话及其消息。 */
    @DeleteMapping("/conversations/{conversationId}")
    @RequiresPermission("QUERY_EXECUTE")
    public void deleteConversation(@PathVariable UUID conversationId) {
        conversationService.delete(conversationId);
    }

    /** 查询请求。 */
    public record QueryRequest(@NotBlank String question, @JsonProperty("useModel") Boolean useModelValue) {
        /** 未传开关时保持历史行为：允许使用已配置大模型。 */
        public boolean useModel() { return useModelValue == null || useModelValue; }
    }

    /** 会话管理请求；字段为空表示保持原值。 */
    public record ConversationPatch(String title, Boolean pinned) { }

    /** 问 Wiki 对大模型调用能力的脱敏状态。 */
    public record ModelStatus(boolean available, boolean enabled, String provider, String modelName, String reason) { }
}
