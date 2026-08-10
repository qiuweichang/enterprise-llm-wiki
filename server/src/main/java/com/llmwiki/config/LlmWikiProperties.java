package com.llmwiki.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 聚合 LLM Wiki 自有配置，避免业务代码直接读取环境变量或散落字符串键。
 * 密码、JWT 密钥和模型密钥仅通过外部环境注入，不提供可提交的默认值。
 */
@ConfigurationProperties(prefix = "llm-wiki")
public record LlmWikiProperties(
        Jwt jwt,
        Storage storage,
        Python python,
        Worker worker,
        Bootstrap bootstrap,
        Llm llm,
        Development development
) {
    /** JWT 签发配置。 */
    public record Jwt(String secret, String issuer, Duration accessTtl, Duration refreshTtl) { }

    /** 原始素材对象存储的本地实现根目录。 */
    public record Storage(String root) { }

    /** 独立 Python 提取服务连接配置。 */
    public record Python(String baseUrl, Duration timeout) { }

    /** 后台任务实例标识、租约和轮询节奏。 */
    public record Worker(String instanceId, Duration leaseDuration, Duration pollDelay) { }

    /** 首次启动管理员引导配置。 */
    public record Bootstrap(boolean enabled, String email, String password) { }

    /** 可选的 OpenAI 兼容模型与密钥加密配置；数据库空间配置优先于这里的环境变量回退值。 */
    public record Llm(String baseUrl, String apiKey, String model, String encryptionKey) { }

    /** 仅开发 profile 可开启的不安全测试凭据配置，生产环境必须保持关闭。 */
    public record Development(boolean allowInsecureCredentials, String account, String password) { }
}
