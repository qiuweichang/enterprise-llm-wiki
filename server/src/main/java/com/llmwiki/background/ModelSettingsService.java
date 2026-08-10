package com.llmwiki.background;

import com.llmwiki.audit.AuditService;
import com.llmwiki.cache.WikiCacheService;
import com.llmwiki.common.ApiException;
import com.llmwiki.common.ContentHash;
import com.llmwiki.config.LlmWikiProperties;
import com.llmwiki.security.AuthenticatedUser;
import com.llmwiki.security.ModelCredentialException;
import com.llmwiki.security.RequestContext;
import com.llmwiki.security.SecretCipher;
import com.llmwiki.security.TenantDatabaseContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.UUID;

/**
 * 管理空间级 AI 模型配置，敏感 API Key 只以 AES-GCM 密文保存并在后台调用前短暂解密。
 */
@Service
public class ModelSettingsService {
    /** 空间模型设置的事务化数据库入口。 */
    private final JdbcClient jdbc;
    /** 确保模型配置读写受 PostgreSQL FORCE RLS 保护。 */
    private final TenantDatabaseContext tenantDatabaseContext;
    /** 仅在保存和实际调用前处理 API Key 明文。 */
    private final SecretCipher secretCipher;
    /** 模型配置变更的同事务审计入口。 */
    private final AuditService auditService;
    /** 提供无数据库记录时的环境变量回退模型。 */
    private final LlmWikiProperties properties;
    /** 模型切换后推进空间版本戳，避免继续命中旧模型生成的 Query 回答。 */
    private final WikiCacheService cacheService;

    /**
     * 创建空间模型设置服务。
     *
     * @param jdbc 数据库客户端
     * @param tenantDatabaseContext 强制 RLS 租户上下文
     * @param secretCipher API Key 认证加密器
     * @param auditService 配置变更审计服务
     * @param properties 环境变量回退配置
     * @param cacheService Query 缓存空间版本服务
     */
    public ModelSettingsService(JdbcClient jdbc, TenantDatabaseContext tenantDatabaseContext,
                                SecretCipher secretCipher, AuditService auditService,
                                LlmWikiProperties properties, WikiCacheService cacheService) {
        this.jdbc = jdbc;
        this.tenantDatabaseContext = tenantDatabaseContext;
        this.secretCipher = secretCipher;
        this.auditService = auditService;
        this.properties = properties;
        this.cacheService = cacheService;
    }

    /**
     * 读取当前空间可公开展示的模型设置，绝不返回 API Key 明文或密文。
     *
     * @return 模型设置视图
     */
    @Transactional(readOnly = true)
    public ModelSettingsView get() {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        StoredModel stored = findStored();
        if (stored != null) {
            return toView(stored, "WORKSPACE");
        }
        LlmWikiProperties.Llm fallback = properties.llm();
        boolean configured = hasText(fallback.baseUrl()) && hasText(fallback.model());
        return new ModelSettingsView(configured, "OPENAI_COMPATIBLE", safe(fallback.baseUrl()),
                safe(fallback.model()), hasText(fallback.apiKey()), hasText(fallback.apiKey()),
                hasText(fallback.apiKey()) ? "环境变量密钥" : null,
                0.20, 4096, "ENVIRONMENT", null, "AVAILABLE", null);
    }

    /**
     * 保存当前空间模型配置；空 API Key 表示保留旧密钥，clearApiKey 才会明确清除。
     *
     * @param request 新模型设置
     * @return 脱敏后的已保存设置
     */
    @Transactional
    public ModelSettingsView update(ModelSettingsRequest request) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        String provider = normalizeProvider(request.provider());
        String baseUrl = normalizeBaseUrl(request.baseUrl());
        String modelName = request.modelName() == null ? "" : request.modelName().trim();
        if (request.enabled() && (baseUrl.isBlank() || modelName.isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_CONFIGURATION_INCOMPLETE", "启用模型前必须填写服务地址和模型名称");
        }
        StoredModel existing = findStored();
        String ciphertext = existing == null ? null : existing.apiKeyCiphertext();
        String hint = existing == null ? null : existing.apiKeyHint();
        boolean inheritEnvironmentKey = existing != null && existing.inheritEnvironmentKey();
        if (request.clearApiKey()) {
            ciphertext = null;
            hint = null;
            inheritEnvironmentKey = false;
        } else if (hasText(request.apiKey())) {
            ciphertext = secretCipher.encrypt(request.apiKey().trim());
            hint = keyHint(request.apiKey().trim());
            inheritEnvironmentKey = false;
        } else if (existing == null && hasText(properties.llm().apiKey())) {
            // 从环境回退首次保存为空间配置时只记录继承标记，不复制环境秘密到数据库。
            inheritEnvironmentKey = true;
            hint = "环境变量密钥";
        }
        double temperature = Math.max(0, Math.min(2, request.temperature()));
        int maxTokens = Math.max(256, Math.min(32768, request.maxTokens()));
        String candidateApiKey;
        try {
            candidateApiKey = ciphertext != null ? secretCipher.decrypt(ciphertext)
                    : inheritEnvironmentKey ? safe(properties.llm().apiKey()) : "";
        } catch (ModelCredentialException exception) {
            if (request.enabled() || hasText(request.apiKey())) {
                throw new ApiException(HttpStatus.CONFLICT, "MODEL_CREDENTIAL_REENTRY_REQUIRED",
                        "原模型密钥已失效，请重新输入接口密钥并测试连接");
            }
            // 允许管理员先停用失效配置；旧密文仍保留，只有明确清除或新密钥保存时才替换。
            candidateApiKey = "";
        }
        String fingerprint = fingerprint(provider, baseUrl, modelName, candidateApiKey, temperature, maxTokens);
        String currentFingerprint = currentFingerprint(existing);
        boolean disablingInvalidCredential = !request.enabled() && currentFingerprint == null
                && !hasText(request.apiKey()) && !request.clearApiKey();
        boolean clearingDisabledCredential = !request.enabled() && request.clearApiKey();
        boolean connectionChanged = currentFingerprint == null || !fingerprint.equals(currentFingerprint);
        if (!disablingInvalidCredential && !clearingDisabledCredential
                && (request.enabled() || connectionChanged) && !hasSuccessfulTest(user, fingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT, "MODEL_TEST_REQUIRED", "请先测试模型连接，测试成功后才能保存");
        }
        jdbc.sql("""
                        insert into workspace_model_settings(organization_id, workspace_id, enabled, provider,
                            base_url, model_name, api_key_ciphertext, inherit_environment_key, api_key_hint,
                            temperature, max_tokens, updated_by)
                        values (:organizationId, :workspaceId, :enabled, :provider, :baseUrl, :modelName,
                                :ciphertext, :inheritEnvironmentKey, :hint, :temperature, :maxTokens, :userId)
                        on conflict (workspace_id) do update set
                            enabled = excluded.enabled, provider = excluded.provider, base_url = excluded.base_url,
                            model_name = excluded.model_name, api_key_ciphertext = excluded.api_key_ciphertext,
                            inherit_environment_key = excluded.inherit_environment_key,
                            api_key_hint = excluded.api_key_hint, temperature = excluded.temperature,
                            max_tokens = excluded.max_tokens, updated_by = excluded.updated_by, updated_at = now()
                        where workspace_model_settings.organization_id = :organizationId
                        """).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("enabled", request.enabled()).param("provider", provider).param("baseUrl", baseUrl)
                .param("modelName", modelName).param("ciphertext", ciphertext).param("hint", hint)
                .param("inheritEnvironmentKey", inheritEnvironmentKey)
                .param("temperature", temperature).param("maxTokens", maxTokens).param("userId", user.userId()).update();
        auditService.record("MODEL_SETTINGS_UPDATED", "WORKSPACE", user.workspaceId(), null,
                java.util.Map.of("enabled", request.enabled(), "provider", provider, "modelName", modelName,
                        "apiKeyConfigured", ciphertext != null || inheritEnvironmentKey,
                        "inheritsEnvironmentKey", inheritEnvironmentKey), java.util.Map.of());
        cacheService.advanceWorkspaceEpoch(user.organizationId(), user.workspaceId());
        return toView(findStored(), "WORKSPACE");
    }

    /**
     * 解析尚未保存的模型表单，供事务外连接测试使用；空密钥会复用当前空间或环境密钥。
     *
     * @param request 待测试配置
     * @return 包含配置指纹的短生命周期测试候选
     */
    @Transactional(readOnly = true)
    public TestCandidate prepareTest(ModelSettingsRequest request) {
        AuthenticatedUser user = RequestContext.require();
        tenantDatabaseContext.applyCurrentRequest();
        String provider = normalizeProvider(request.provider());
        String baseUrl = normalizeBaseUrl(request.baseUrl());
        String modelName = request.modelName() == null ? "" : request.modelName().trim();
        if (baseUrl.isBlank() || modelName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_CONFIGURATION_INCOMPLETE", "请填写服务地址和模型名称");
        }
        StoredModel existing = findStored();
        String apiKey;
        if (hasText(request.apiKey())) {
            apiKey = request.apiKey().trim();
        } else if (!request.clearApiKey() && existing != null && existing.apiKeyCiphertext() != null) {
            try {
                apiKey = secretCipher.decrypt(existing.apiKeyCiphertext());
            } catch (ModelCredentialException exception) {
                throw new ApiException(HttpStatus.CONFLICT, "MODEL_CREDENTIAL_REENTRY_REQUIRED",
                        "原模型密钥已失效，请重新输入接口密钥后再测试");
            }
        } else if (!request.clearApiKey() && ((existing != null && existing.inheritEnvironmentKey()) || existing == null)) {
            apiKey = safe(properties.llm().apiKey());
        } else {
            apiKey = "";
        }
        double temperature = Math.max(0, Math.min(2, request.temperature()));
        int maxTokens = Math.max(256, Math.min(32768, request.maxTokens()));
        RuntimeModel model = new RuntimeModel(provider, baseUrl, modelName, apiKey, temperature, maxTokens);
        return new TestCandidate(user.organizationId(), user.workspaceId(), user.userId(), model,
                fingerprint(provider, baseUrl, modelName, apiKey, temperature, maxTokens));
    }

    /**
     * 持久化成功连接测试，十分钟内仅允许同一用户保存完全相同的配置。
     *
     * @param candidate 已实际调用成功的配置候选
     * @param latencyMs 模型端到端延迟
     * @param responsePreview 脱敏后的简短响应
     */
    @Transactional
    public void recordSuccessfulTest(TestCandidate candidate, long latencyMs, String responsePreview) {
        tenantDatabaseContext.apply(candidate.organizationId(), candidate.workspaceId());
        jdbc.sql("""
                        insert into workspace_model_connection_tests(organization_id, workspace_id, user_id,
                            configuration_fingerprint, provider, model_name, latency_ms, response_preview, expires_at)
                        values (:organizationId, :workspaceId, :userId, :fingerprint, :provider, :modelName,
                                :latencyMs, :preview, now() + interval '10 minutes')
                        on conflict (workspace_id, user_id) do update set
                            configuration_fingerprint = excluded.configuration_fingerprint,
                            provider = excluded.provider, model_name = excluded.model_name,
                            latency_ms = excluded.latency_ms, response_preview = excluded.response_preview,
                            tested_at = now(), expires_at = excluded.expires_at
                        where workspace_model_connection_tests.organization_id = :organizationId
                        """).param("organizationId", candidate.organizationId())
                .param("workspaceId", candidate.workspaceId()).param("userId", candidate.userId())
                .param("fingerprint", candidate.fingerprint()).param("provider", candidate.model().provider())
                .param("modelName", candidate.model().modelName()).param("latencyMs", latencyMs)
                .param("preview", responsePreview).update();
    }

    /**
     * 为指定后台租户解析实际运行配置；空间记录存在时优先并可显式关闭环境回退模型。
     *
     * @param organizationId 组织 ID
     * @param workspaceId 空间 ID
     * @return 可调用模型；未启用时返回 null
     */
    @Transactional(readOnly = true)
    public RuntimeModel resolve(UUID organizationId, UUID workspaceId) {
        tenantDatabaseContext.apply(organizationId, workspaceId);
        StoredModel stored = findStored();
        if (stored != null) {
            if (!stored.enabled() || !hasText(stored.baseUrl()) || !hasText(stored.modelName())) {
                return null;
            }
            String apiKey = stored.apiKeyCiphertext() != null ? secretCipher.decrypt(stored.apiKeyCiphertext())
                    : stored.inheritEnvironmentKey() ? safe(properties.llm().apiKey()) : "";
            return new RuntimeModel(stored.provider(), stored.baseUrl(), stored.modelName(), apiKey,
                    stored.temperature(), stored.maxTokens());
        }
        LlmWikiProperties.Llm fallback = properties.llm();
        if (!hasText(fallback.baseUrl()) || !hasText(fallback.model())) {
            return null;
        }
        return new RuntimeModel("OPENAI_COMPATIBLE", normalizeBaseUrl(fallback.baseUrl()), fallback.model().trim(),
                safe(fallback.apiKey()), 0.20, 4096);
    }

    /**
     * 校验指定空间是否存在可解析的启用模型，供持续优化开关保存前阻止无效调度。
     *
     * @param organizationId 组织 ID
     * @param workspaceId 空间 ID
     */
    public void requireRunnable(UUID organizationId, UUID workspaceId) {
        try {
            if (resolve(organizationId, workspaceId) == null) {
                throw new ApiException(HttpStatus.CONFLICT, "EVOLUTION_MODEL_REQUIRED",
                        "请先启用并测试保存大模型，再开启持续优化");
            }
        } catch (ModelCredentialException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "MODEL_CREDENTIAL_REENTRY_REQUIRED",
                    "模型接口密钥已失效，请重新输入密钥并测试保存后再开启持续优化");
        }
    }

    /** 按当前已应用的 RLS 上下文查找空间配置。 */
    private StoredModel findStored() {
        return jdbc.sql("""
                        select enabled, provider, base_url, model_name, api_key_ciphertext,
                               inherit_environment_key, api_key_hint,
                               temperature, max_tokens, updated_at
                        from workspace_model_settings
                        """).query((rs, rowNum) -> new StoredModel(rs.getBoolean("enabled"), rs.getString("provider"),
                        rs.getString("base_url"), rs.getString("model_name"), rs.getString("api_key_ciphertext"),
                        rs.getBoolean("inherit_environment_key"), rs.getString("api_key_hint"),
                        rs.getDouble("temperature"), rs.getInt("max_tokens"),
                        rs.getTimestamp("updated_at").toInstant())).optional().orElse(null);
    }

    /** 转换为不含秘密的前端视图。 */
    private ModelSettingsView toView(StoredModel model, String source) {
        CredentialState credential = credentialState(model);
        return new ModelSettingsView(model.enabled(), model.provider(), model.baseUrl(), model.modelName(),
                model.apiKeyCiphertext() != null || model.inheritEnvironmentKey(), model.inheritEnvironmentKey(),
                model.apiKeyHint(),
                model.temperature(), model.maxTokens(),
                source, model.updatedAt(), credential.status(), credential.message());
    }

    /** 判断已保存密文能否由当前服务端主密钥解密，但绝不向前端暴露明文。 */
    private CredentialState credentialState(StoredModel model) {
        if (model.apiKeyCiphertext() == null) {
            return new CredentialState("AVAILABLE", null);
        }
        try {
            secretCipher.decrypt(model.apiKeyCiphertext());
            return new CredentialState("AVAILABLE", null);
        } catch (ModelCredentialException exception) {
            return new CredentialState("INVALID", "接口密钥无法解密，请重新输入密钥并测试保存");
        }
    }

    /** 判断当前用户是否在有效期内测试过完全相同的模型配置。 */
    private boolean hasSuccessfulTest(AuthenticatedUser user, String fingerprint) {
        return jdbc.sql("""
                        select exists(select 1 from workspace_model_connection_tests
                            where organization_id = :organizationId and workspace_id = :workspaceId
                              and user_id = :userId and configuration_fingerprint = :fingerprint
                              and expires_at > now())
                        """).param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId())
                .param("userId", user.userId()).param("fingerprint", fingerprint).query(Boolean.class).single();
    }

    /**
     * 计算保存前的有效配置指纹，用于区分“只停用现有模型”和实际修改模型参数。
     *
     * @param existing 当前空间配置；为空时使用环境回退配置
     * @return 当前有效连接配置的指纹
     */
    private String currentFingerprint(StoredModel existing) {
        if (existing != null) {
            String apiKey;
            try {
                apiKey = existing.apiKeyCiphertext() != null
                        ? secretCipher.decrypt(existing.apiKeyCiphertext())
                        : existing.inheritEnvironmentKey() ? safe(properties.llm().apiKey()) : "";
            } catch (ModelCredentialException exception) {
                return null;
            }
            return fingerprint(existing.provider(), existing.baseUrl(), existing.modelName(), apiKey,
                    existing.temperature(), existing.maxTokens());
        }
        LlmWikiProperties.Llm fallback = properties.llm();
        return fingerprint("OPENAI_COMPATIBLE", normalizeBaseUrl(fallback.baseUrl()), safe(fallback.model()).trim(),
                safe(fallback.apiKey()), 0.20, 4096);
    }

    /** 生成包含密钥摘要的配置指纹，既能约束测试一致性又不保存密钥明文。 */
    private String fingerprint(String provider, String baseUrl, String modelName, String apiKey,
                               double temperature, int maxTokens) {
        return ContentHash.sha256(String.join("\n", provider, baseUrl, modelName,
                ContentHash.sha256(safe(apiKey)), Double.toString(temperature), Integer.toString(maxTokens)));
    }

    /** 校验并规范 OpenAI 兼容端点，允许企业内网 HTTP 服务。 */
    private String normalizeBaseUrl(String value) {
        if (!hasText(value)) {
            return "";
        }
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MODEL_BASE_URL", "模型服务地址格式无效");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MODEL_BASE_URL", "模型服务必须是无内嵌凭据的 HTTP/HTTPS 地址");
        }
        return value.trim().replaceAll("/+$", "");
    }

    /** 规范当前支持的模型协议类型。 */
    private String normalizeProvider(String value) {
        String provider = hasText(value) ? value.trim().toUpperCase() : "OPENAI_COMPATIBLE";
        if (!java.util.Set.of("OPENAI_COMPATIBLE", "OPENAI", "OLLAMA").contains(provider)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_MODEL_PROVIDER", "当前仅支持 OpenAI 兼容协议或 Ollama 兼容端点");
        }
        return provider;
    }

    /** 生成只暴露少量首尾字符的密钥提示。 */
    private String keyHint(String value) {
        return value.length() <= 8 ? "已配置" : value.substring(0, 4) + "••••" + value.substring(value.length() - 4);
    }

    /** 返回空安全字符串。 */
    private String safe(String value) {
        return value == null ? "" : value;
    }

    /** 统一判断配置文本是否有效。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 模型设置更新请求。 */
    public record ModelSettingsRequest(boolean enabled, String provider, String baseUrl, String modelName,
                                       String apiKey, boolean clearApiKey, double temperature, int maxTokens) { }
    /** 前端可见的脱敏模型设置。 */
    public record ModelSettingsView(boolean enabled, String provider, String baseUrl, String modelName,
                                    boolean apiKeyConfigured, boolean inheritsEnvironmentKey, String apiKeyHint,
                                    double temperature, int maxTokens, String source,
                                    java.time.Instant updatedAt, String credentialStatus,
                                    String credentialMessage) { }
    /** 后台调用模型所需的短生命周期配置。 */
    public record RuntimeModel(String provider, String baseUrl, String modelName, String apiKey,
                               double temperature, int maxTokens) { }
    /** 连接测试期间使用且不会持久化秘密的配置候选。 */
    public record TestCandidate(UUID organizationId, UUID workspaceId, UUID userId,
                                RuntimeModel model, String fingerprint) { }
    /** 数据库模型配置行。 */
    private record StoredModel(boolean enabled, String provider, String baseUrl, String modelName,
                               String apiKeyCiphertext, boolean inheritEnvironmentKey, String apiKeyHint,
                               double temperature, int maxTokens,
                               java.time.Instant updatedAt) { }
    /** 前端可见的凭据健康状态，不包含任何密钥内容。 */
    private record CredentialState(String status, String message) { }
}
