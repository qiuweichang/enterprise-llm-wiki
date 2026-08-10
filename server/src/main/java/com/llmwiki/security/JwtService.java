package com.llmwiki.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.llmwiki.common.ApiException;
import com.llmwiki.config.LlmWikiProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * 负责短期访问 JWT 与不透明刷新令牌的生成和校验。
 * 刷新令牌仅以 SHA-256 摘要持久化，数据库泄露时无法直接复用原令牌。
 */
@Service
public class JwtService {
    private final LlmWikiProperties.Jwt properties;
    private final Algorithm algorithm;
    private final JWTVerifier verifier;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 创建 JWT 服务并验证密钥强度。
     *
     * @param properties 应用配置
     */
    public JwtService(LlmWikiProperties properties) {
        this.properties = properties.jwt();
        if (this.properties.secret() == null || this.properties.secret().length() < 32) {
            throw new IllegalStateException("LLM_WIKI_JWT_SECRET must contain at least 32 characters");
        }
        this.algorithm = Algorithm.HMAC256(this.properties.secret());
        this.verifier = JWT.require(algorithm).withIssuer(this.properties.issuer()).build();
    }

    /**
     * 为已确定租户上下文的会话签发短期访问令牌。
     *
     * @param userId 用户 ID
     * @param organizationId 组织 ID
     * @param workspaceId 工作空间 ID
     * @param sessionId 会话 ID
     * @param tokenVersion 用户令牌版本，用于全局失效
     * @return 已签名 JWT
     */
    public String issueAccessToken(UUID userId, UUID organizationId, UUID workspaceId, UUID sessionId, long tokenVersion) {
        Instant now = Instant.now();
        return JWT.create()
                .withIssuer(properties.issuer())
                .withSubject(userId.toString())
                .withClaim("org", organizationId.toString())
                .withClaim("workspace", workspaceId.toString())
                .withClaim("session", sessionId.toString())
                .withClaim("tokenVersion", tokenVersion)
                .withIssuedAt(now)
                .withExpiresAt(now.plus(properties.accessTtl()))
                .sign(algorithm);
    }

    /**
     * 验证访问令牌签名、签发者和有效期，并提取最小身份声明。
     *
     * @param token Bearer JWT
     * @return 解码后的身份声明
     */
    public TokenClaims verifyAccessToken(String token) {
        try {
            DecodedJWT jwt = verifier.verify(token);
            return new TokenClaims(
                    UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaim("org").asString()),
                    UUID.fromString(jwt.getClaim("workspace").asString()),
                    UUID.fromString(jwt.getClaim("session").asString()),
                    jwt.getClaim("tokenVersion").asLong()
            );
        } catch (JWTVerificationException | IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "登录凭证无效或已过期");
        }
    }

    /**
     * 生成高熵不透明刷新令牌。
     *
     * @return URL 安全的随机令牌
     */
    public String newRefreshToken() {
        byte[] bytes = new byte[48];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 对刷新令牌生成稳定摘要用于数据库查找。
     *
     * @param token 原始刷新令牌
     * @return 小写十六进制 SHA-256 摘要
     */
    public String hashRefreshToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** @return 刷新令牌生命周期 */
    public java.time.Duration refreshTtl() {
        return properties.refreshTtl();
    }

    /** 访问令牌中的最小可信身份声明。 */
    public record TokenClaims(UUID userId, UUID organizationId, UUID workspaceId, UUID sessionId, long tokenVersion) { }
}
