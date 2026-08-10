package com.llmwiki.background;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 统一调用空间配置的 OpenAI 兼容 Chat Completions 端点，供摄取编译和持续优化共同复用。
 */
@Component
public class OpenAiCompatibleClient {
    /** 防止外部模型异常时无限占用虚拟线程和后台运行租约。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);
    /** 保存前测试使用更短超时，避免错误地址让管理员长时间等待。 */
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(20);
    /** 按组织和空间解析数据库优先、环境变量回退的模型配置。 */
    private final ModelSettingsService modelSettingsService;
    /** 可跨不同空间模型地址复用的 JDK HTTP 连接工厂。 */
    private final JdkClientHttpRequestFactory requestFactory;

    /** 创建可配置模型客户端。 */
    public OpenAiCompatibleClient(ModelSettingsService modelSettingsService) {
        this.modelSettingsService = modelSettingsService;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.requestFactory = new JdkClientHttpRequestFactory(httpClient);
        this.requestFactory.setReadTimeout(READ_TIMEOUT);
    }

    /**
     * 发送要求严格 JSON 的模型请求。
     *
     * @param organizationId 组织 ID
     * @param workspaceId 空间 ID
     * @param systemPrompt 系统约束
     * @param userPrompt 业务输入
     * @return 模型正文与实际模型标识；未启用模型时返回 null
     */
    public Completion completeJson(UUID organizationId, UUID workspaceId, String systemPrompt, String userPrompt) {
        return complete(organizationId, workspaceId, systemPrompt, userPrompt, true);
    }

    /**
     * 发送普通文本模型请求，供带引用的 Query 回答链路使用。
     *
     * @param organizationId 组织 ID
     * @param workspaceId 空间 ID
     * @param systemPrompt 系统约束
     * @param userPrompt 业务输入
     * @return 模型正文与实际模型标识；未启用模型时返回 null
     */
    public Completion completeText(UUID organizationId, UUID workspaceId, String systemPrompt, String userPrompt) {
        return complete(organizationId, workspaceId, systemPrompt, userPrompt, false);
    }

    /**
     * 实际调用未保存的模型配置，只有收到非空回答才视为连接测试成功。
     *
     * @param model 待测试运行配置
     * @return 延迟和简短响应预览
     */
    public ConnectionTestResult testConnection(ModelSettingsService.RuntimeModel model) {
        long started = System.nanoTime();
        JdkClientHttpRequestFactory testRequestFactory = requestFactory(TEST_TIMEOUT);
        Completion completion = complete(model, "你正在执行模型连接测试。",
                "请只回复：连接成功", false, testRequestFactory);
        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        String preview = completion.content().replaceAll("\\s+", " ").trim();
        if (preview.length() > 120) {
            preview = preview.substring(0, 120) + "…";
        }
        return new ConnectionTestResult(latencyMs, preview);
    }

    /**
     * 根据输出类型构造统一 Chat Completions 请求。
     *
     * @param organizationId 组织 ID
     * @param workspaceId 空间 ID
     * @param systemPrompt 系统约束
     * @param userPrompt 业务输入
     * @param jsonOutput 是否要求兼容端点返回 JSON 对象
     * @return 模型响应；当前空间未启用模型时返回 null
     */
    private Completion complete(UUID organizationId, UUID workspaceId, String systemPrompt, String userPrompt,
                                boolean jsonOutput) {
        ModelSettingsService.RuntimeModel model = modelSettingsService.resolve(organizationId, workspaceId);
        if (model == null) {
            return null;
        }
        return complete(model, systemPrompt, userPrompt, jsonOutput);
    }

    /** 向已解析的运行配置发送请求，供正式调用和保存前测试复用。 */
    private Completion complete(ModelSettingsService.RuntimeModel model, String systemPrompt, String userPrompt,
                                boolean jsonOutput) {
        return complete(model, systemPrompt, userPrompt, jsonOutput, requestFactory);
    }

    /** 使用指定网络超时发送请求，使正式任务和连接测试可以采用不同等待上限。 */
    private Completion complete(ModelSettingsService.RuntimeModel model, String systemPrompt, String userPrompt,
                                boolean jsonOutput, JdkClientHttpRequestFactory activeRequestFactory) {
        RestClient client = RestClient.builder().baseUrl(model.baseUrl()).requestFactory(activeRequestFactory).build();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.modelName());
        body.put("temperature", model.temperature());
        body.put("max_tokens", model.maxTokens());
        if (jsonOutput) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        body.put("messages", List.of(Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        RestClient.RequestBodySpec request = client.post().uri("/chat/completions");
        if (model.apiKey() != null && !model.apiKey().isBlank()) {
            request.header("Authorization", "Bearer " + model.apiKey());
        }
        JsonNode response;
        try {
            response = request.body(body).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 429) {
                throw new ModelQuotaExceededException(quotaMessage(exception), exception);
            }
            throw exception;
        }
        if (response == null || response.at("/choices/0/message/content").asText().isBlank()) {
            throw new IllegalStateException("Configured model returned no completion content");
        }
        return new Completion(response.at("/choices/0/message/content").asText(), model.provider(), model.modelName());
    }

    /** 从供应商 429 响应中提取重置时间等信息，避免把原始 JSON 直接暴露给用户。 */
    private String quotaMessage(RestClientResponseException exception) {
        String body = exception.getResponseBodyAsString();
        try {
            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            String detail = root.at("/error/message").asText("");
            if (!detail.isBlank()) return "模型额度已达到服务商限制：" + detail;
        } catch (Exception ignored) {
            // 供应商返回非 JSON 时使用统一额度提示。
        }
        return "模型额度已达到服务商限制，请等待额度窗口重置后重试。";
    }

    /** 创建共享连接策略下、指定读取超时的请求工厂。 */
    private JdkClientHttpRequestFactory requestFactory(Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    /** 模型单次响应及可审计标识。 */
    public record Completion(String content, String provider, String modelName) { }
    /** 保存前连接测试结果。 */
    public record ConnectionTestResult(long latencyMs, String responsePreview) { }
}
