package com.llmwiki.background;

import com.llmwiki.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 编排模型保存前的真实连接测试，确保外部网络调用不会占用数据库事务。
 */
@Service
public class ModelConnectionTestService {
    private static final Logger log = LoggerFactory.getLogger(ModelConnectionTestService.class);
    /** 负责解析表单、生成指纹和记录成功结果。 */
    private final ModelSettingsService modelSettingsService;
    /** 负责对候选端点发出真实 Chat Completions 请求。 */
    private final OpenAiCompatibleClient modelClient;

    /**
     * 创建模型连接测试服务。
     *
     * @param modelSettingsService 模型配置服务
     * @param modelClient OpenAI 兼容调用客户端
     */
    public ModelConnectionTestService(ModelSettingsService modelSettingsService,
                                      OpenAiCompatibleClient modelClient) {
        this.modelSettingsService = modelSettingsService;
        this.modelClient = modelClient;
    }

    /**
     * 测试表单中的模型并记录十分钟有效的成功凭证。
     *
     * @param request 尚未保存的模型配置
     * @return 测试延迟和响应预览
     */
    public TestView test(ModelSettingsService.ModelSettingsRequest request) {
        ModelSettingsService.TestCandidate candidate = modelSettingsService.prepareTest(request);
        try {
            OpenAiCompatibleClient.ConnectionTestResult result = modelClient.testConnection(candidate.model());
            modelSettingsService.recordSuccessfulTest(candidate, result.latencyMs(), result.responsePreview());
            log.info("Model connection test succeeded workspaceId={} provider={} model={} latencyMs={}",
                    candidate.workspaceId(), candidate.model().provider(), candidate.model().modelName(),
                    result.latencyMs());
            return new TestView(true, result.latencyMs(), result.responsePreview());
        } catch (Exception exception) {
            log.error("Model connection test failed workspaceId={} provider={} model={}", candidate.workspaceId(),
                    candidate.model().provider(), candidate.model().modelName(), exception);
            boolean quota = hasCause(exception, ModelQuotaExceededException.class);
            String message = quota ? quotaMessage(exception) : "模型连接失败：" + safeMessage(exception);
            throw new ApiException(HttpStatus.BAD_REQUEST, quota ? "MODEL_QUOTA_EXCEEDED" : "MODEL_CONNECTION_FAILED", message);
        }
    }

    /** 将额度异常统一转换为明确的中文提示。 */
    private String quotaMessage(Exception exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ModelQuotaExceededException) return current.getMessage();
            current = current.getCause();
        }
        return "模型额度已达到服务商限制，请等待额度窗口重置后重试。";
    }

    /** 沿异常链判断是否包含指定类型。 */
    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) { if (type.isInstance(current)) return true; current = current.getCause(); }
        return false;
    }

    /** 将外部异常压缩为可展示且不包含请求秘密的消息。 */
    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        message = message.replaceAll("(?i)bearer\\s+[^\\s,]+", "Bearer [已隐藏]");
        return message.length() <= 300 ? message : message.substring(0, 300) + "…";
    }

    /** 前端可展示的模型测试结果。 */
    public record TestView(boolean success, long latencyMs, String responsePreview) { }
}
