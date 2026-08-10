package com.llmwiki.security;

import com.llmwiki.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 实现不依赖 Spring Security 的登录、令牌轮换、会话校验和当前身份查询。
 */
@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private final JdbcClient jdbc;
    private final PasswordService passwordService;
    private final JwtService jwtService;

    /**
     * 创建认证服务。
     *
     * @param jdbc 数据库访问组件
     * @param passwordService 密码哈希组件
     * @param jwtService 令牌组件
     */
    public AuthService(JdbcClient jdbc, PasswordService passwordService, JwtService jwtService) {
        this.jdbc = jdbc;
        this.passwordService = passwordService;
        this.jwtService = jwtService;
    }

    /**
     * 验证账号密码、选择授权空间并创建可撤销会话。
     *
     * @param email 登录邮箱
     * @param password 明文密码
     * @param requestedWorkspaceId 可选的目标空间
     * @param request HTTP 请求，用于记录会话设备和 IP
     * @return 登录结果及一次性返回的刷新令牌
     */
    @Transactional
    public LoginResult login(String identifier, String password, UUID requestedWorkspaceId, HttpServletRequest request) {
        UserRow user = findUserByIdentifier(identifier);
        if (!passwordService.verify(password, user.passwordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码错误");
        }
        MembershipRow membership = findMembership(user.id(), requestedWorkspaceId);
        Set<String> permissions = loadPermissions(user.id(), membership.organizationId(), membership.workspaceId());
        UUID sessionId = UUID.randomUUID();
        String refreshToken = jwtService.newRefreshToken();
        Instant expiresAt = Instant.now().plus(jwtService.refreshTtl());
        jdbc.sql("""
                        insert into user_sessions(id, user_id, organization_id, workspace_id, refresh_token_hash,
                                                  user_agent, ip_address, expires_at)
                        values (:id, :userId, :organizationId, :workspaceId, :tokenHash, :userAgent,
                                cast(:ipAddress as inet), :expiresAt)
                        """)
                .param("id", sessionId)
                .param("userId", user.id())
                .param("organizationId", membership.organizationId())
                .param("workspaceId", membership.workspaceId())
                .param("tokenHash", jwtService.hashRefreshToken(refreshToken))
                .param("userAgent", request.getHeader("User-Agent"))
                .param("ipAddress", normalizeIp(request.getRemoteAddr()))
                .param("expiresAt", sqlTimestamp(expiresAt))
                .update();
        jdbc.sql("update users set last_login_at = now(), updated_at = now() where id = :id")
                .param("id", user.id()).update();
        String accessToken = jwtService.issueAccessToken(user.id(), membership.organizationId(),
                membership.workspaceId(), sessionId, user.tokenVersion());
        log.info("User login succeeded userId={} organizationId={} workspaceId={}", user.id(),
                membership.organizationId(), membership.workspaceId());
        return new LoginResult(accessToken, refreshToken, expiresAt,
                new CurrentUser(user.id(), user.email(), user.displayName(), membership.organizationId(),
                        membership.organizationName(), membership.workspaceId(), membership.workspaceName(), permissions));
    }

    /**
     * 校验并轮换刷新令牌；旧令牌在同一事务内立即失效，降低重放风险。
     *
     * @param refreshToken 客户端 HttpOnly Cookie 中的令牌
     * @return 新访问令牌和新刷新令牌
     */
    @Transactional
    public LoginResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "MISSING_REFRESH_TOKEN", "刷新凭证缺失");
        }
        SessionRow session = jdbc.sql("""
                        select s.id, s.user_id, s.organization_id, s.workspace_id, s.expires_at,
                               u.email, u.display_name, u.status, u.token_version,
                               o.name as organization_name, w.name as workspace_name
                        from user_sessions s
                        join users u on u.id = s.user_id
                        join organizations o on o.id = s.organization_id and o.status = 'ACTIVE'
                        join workspaces w on w.id = s.workspace_id and w.status = 'ACTIVE'
                        join workspace_members wm on wm.workspace_id = s.workspace_id
                            and wm.user_id = s.user_id and wm.status = 'ACTIVE'
                        where s.refresh_token_hash = :tokenHash
                          and s.revoked_at is null and s.expires_at > now() and u.status = 'ACTIVE'
                        for update of s
                        """)
                .param("tokenHash", jwtService.hashRefreshToken(refreshToken))
                .query(AuthService::mapSession)
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "刷新凭证无效或已过期"));
        String nextRefreshToken = jwtService.newRefreshToken();
        Instant nextExpiry = Instant.now().plus(jwtService.refreshTtl());
        jdbc.sql("update user_sessions set refresh_token_hash = :hash, expires_at = :expiresAt where id = :id")
                .param("hash", jwtService.hashRefreshToken(nextRefreshToken))
                .param("expiresAt", sqlTimestamp(nextExpiry))
                .param("id", session.id())
                .update();
        Set<String> permissions = loadPermissions(session.userId(), session.organizationId(), session.workspaceId());
        String accessToken = jwtService.issueAccessToken(session.userId(), session.organizationId(), session.workspaceId(),
                session.id(), session.tokenVersion());
        return new LoginResult(accessToken, nextRefreshToken, nextExpiry,
                new CurrentUser(session.userId(), session.email(), session.displayName(), session.organizationId(),
                        session.organizationName(), session.workspaceId(), session.workspaceName(), permissions));
    }

    /**
     * 校验访问令牌声明仍与数据库会话、成员状态和令牌版本一致。
     *
     * @param claims 已完成密码学校验的 JWT 声明
     * @return 可写入请求上下文的完整身份
     */
    @Transactional(readOnly = true)
    public AuthenticatedUser authenticate(JwtService.TokenClaims claims) {
        AuthenticatedRow row = jdbc.sql("""
                        select u.email, u.display_name, u.token_version
                        from user_sessions s
                        join users u on u.id = s.user_id and u.status = 'ACTIVE'
                        join organization_members om on om.organization_id = s.organization_id
                            and om.user_id = s.user_id and om.status = 'ACTIVE'
                        join workspace_members wm on wm.workspace_id = s.workspace_id
                            and wm.user_id = s.user_id and wm.status = 'ACTIVE'
                        join organizations o on o.id = s.organization_id and o.status = 'ACTIVE'
                        join workspaces w on w.id = s.workspace_id and w.status = 'ACTIVE'
                        where s.id = :sessionId and s.user_id = :userId
                          and s.organization_id = :organizationId and s.workspace_id = :workspaceId
                          and s.revoked_at is null and s.expires_at > now()
                        """)
                .param("sessionId", claims.sessionId())
                .param("userId", claims.userId())
                .param("organizationId", claims.organizationId())
                .param("workspaceId", claims.workspaceId())
                .query((rs, rowNum) -> new AuthenticatedRow(rs.getString("email"), rs.getString("display_name"),
                        rs.getLong("token_version")))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REVOKED", "会话已失效，请重新登录"));
        if (row.tokenVersion() != claims.tokenVersion()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED", "登录凭证已失效");
        }
        return new AuthenticatedUser(claims.userId(), claims.organizationId(), claims.workspaceId(), claims.sessionId(),
                row.email(), row.displayName(), row.tokenVersion(),
                loadPermissions(claims.userId(), claims.organizationId(), claims.workspaceId()));
    }

    /**
     * 校验用于 MCP/自动化的长期 API Key，并加载其固定租户权限上下文。
     * 数据库仅保存令牌摘要，原始令牌只在创建时返回一次。
     *
     * @param rawToken 以 lwk_ 开头的原始 API Key
     * @return 完整请求身份
     */
    @Transactional
    public AuthenticatedUser authenticateApiKey(String rawToken) {
        ApiKeyRow row = jdbc.sql("""
                        select k.id, k.user_id, k.organization_id, k.workspace_id,
                               u.email, u.display_name, u.token_version
                        from api_keys k
                        join users u on u.id = k.user_id and u.status = 'ACTIVE'
                        join organization_members om on om.organization_id = k.organization_id
                            and om.user_id = k.user_id and om.status = 'ACTIVE'
                        join workspace_members wm on wm.workspace_id = k.workspace_id
                            and wm.user_id = k.user_id and wm.status = 'ACTIVE'
                        join organizations o on o.id = k.organization_id and o.status = 'ACTIVE'
                        join workspaces w on w.id = k.workspace_id and w.status = 'ACTIVE'
                        where k.token_hash = :tokenHash and k.revoked_at is null
                          and (k.expires_at is null or k.expires_at > now())
                        """).param("tokenHash", jwtService.hashRefreshToken(rawToken))
                .query((rs, rowNum) -> new ApiKeyRow((UUID) rs.getObject("id"), (UUID) rs.getObject("user_id"),
                        (UUID) rs.getObject("organization_id"), (UUID) rs.getObject("workspace_id"),
                        rs.getString("email"), rs.getString("display_name"), rs.getLong("token_version")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", "API Key 无效或已撤销"));
        jdbc.sql("update api_keys set last_used_at = now() where id = :id").param("id", row.id()).update();
        return new AuthenticatedUser(row.userId(), row.organizationId(), row.workspaceId(), row.id(), row.email(),
                row.displayName(), row.tokenVersion(), loadPermissions(row.userId(), row.organizationId(), row.workspaceId()));
    }

    /**
     * 创建一个绑定当前组织/空间和用户权限的 MCP API Key。
     *
     * @param name 用户可识别名称
     * @param expiresAt 可选过期时间
     * @return 只显示一次的原始令牌
     */
    @Transactional
    public CreatedApiKey createApiKey(String name, Instant expiresAt) {
        AuthenticatedUser user = RequestContext.require();
        if (!user.hasPermission("MCP_USE")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "当前角色不能创建 MCP API Key");
        }
        if (name == null || name.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "API_KEY_NAME_REQUIRED", "API Key 名称不能为空");
        }
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_API_KEY_EXPIRY", "过期时间必须晚于当前时间");
        }
        String token = "lwk_" + jwtService.newRefreshToken();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into api_keys(id, user_id, organization_id, workspace_id, name, token_hash,
                                             token_prefix, expires_at)
                        values (:id, :userId, :organizationId, :workspaceId, :name, :hash, :prefix, :expiresAt)
                        """).param("id", id).param("userId", user.userId()).param("organizationId", user.organizationId())
                .param("workspaceId", user.workspaceId()).param("name", name.trim())
                .param("hash", jwtService.hashRefreshToken(token)).param("prefix", token.substring(0, 12))
                .param("expiresAt", sqlTimestamp(expiresAt)).update();
        log.info("MCP API key created keyId={} userId={} workspaceId={}", id, user.userId(), user.workspaceId());
        return new CreatedApiKey(id, name.trim(), token, expiresAt);
    }

    /**
     * 列出当前用户在当前空间的 API Key 元数据，不返回秘密。
     */
    @Transactional(readOnly = true)
    public List<ApiKeyView> apiKeys() {
        AuthenticatedUser user = RequestContext.require();
        return jdbc.sql("""
                        select id, name, token_prefix, expires_at, last_used_at, revoked_at, created_at
                        from api_keys where user_id = :userId and organization_id = :organizationId
                          and workspace_id = :workspaceId order by created_at desc
                        """).param("userId", user.userId()).param("organizationId", user.organizationId())
                .param("workspaceId", user.workspaceId()).query((rs, rowNum) -> new ApiKeyView(
                        (UUID) rs.getObject("id"), rs.getString("name"), rs.getString("token_prefix"),
                        nullableInstant(rs.getTimestamp("expires_at")), nullableInstant(rs.getTimestamp("last_used_at")),
                        nullableInstant(rs.getTimestamp("revoked_at")), rs.getTimestamp("created_at").toInstant())).list();
    }

    /**
     * 撤销当前用户拥有的 API Key。
     *
     * @param keyId API Key ID
     */
    @Transactional
    public void revokeApiKey(UUID keyId) {
        AuthenticatedUser user = RequestContext.require();
        int updated = jdbc.sql("""
                        update api_keys set revoked_at = now()
                        where id = :id and user_id = :userId and organization_id = :organizationId
                          and workspace_id = :workspaceId and revoked_at is null
                        """).param("id", keyId).param("userId", user.userId())
                .param("organizationId", user.organizationId()).param("workspaceId", user.workspaceId()).update();
        if (updated != 1) {
            throw new ApiException(HttpStatus.NOT_FOUND, "API_KEY_NOT_FOUND", "API Key 不存在或已撤销");
        }
        log.info("MCP API key revoked keyId={} userId={}", keyId, user.userId());
    }

    /**
     * 撤销当前会话，使访问令牌在下一次请求时立即被数据库校验拒绝。
     */
    @Transactional
    public void logout() {
        AuthenticatedUser user = RequestContext.require();
        jdbc.sql("update user_sessions set revoked_at = now() where id = :id and user_id = :userId")
                .param("id", user.sessionId()).param("userId", user.userId()).update();
        log.info("User logout succeeded userId={} sessionId={}", user.userId(), user.sessionId());
    }

    /**
     * 返回当前请求的用户与租户概要。
     *
     * @return 当前用户信息
     */
    @Transactional(readOnly = true)
    public CurrentUser currentUser() {
        AuthenticatedUser user = RequestContext.require();
        MembershipRow membership = findMembership(user.userId(), user.workspaceId());
        return new CurrentUser(user.userId(), user.email(), user.displayName(), user.organizationId(),
                membership.organizationName(), user.workspaceId(), membership.workspaceName(), user.permissions());
    }

    /**
     * 列出当前用户可进入的所有组织/空间，用于前端租户切换器。
     *
     * @return 可访问空间列表
     */
    @Transactional(readOnly = true)
    public List<WorkspaceOption> workspaces() {
        AuthenticatedUser user = RequestContext.require();
        return jdbc.sql("""
                        select o.id as organization_id, o.name as organization_name,
                               w.id as workspace_id, w.name as workspace_name, w.description
                        from workspace_members wm
                        join workspaces w on w.id = wm.workspace_id and w.organization_id = wm.organization_id
                        join organizations o on o.id = w.organization_id
                        join organization_members om on om.organization_id = o.id and om.user_id = wm.user_id
                        where wm.user_id = :userId and wm.status = 'ACTIVE' and om.status = 'ACTIVE'
                          and w.status = 'ACTIVE' and o.status = 'ACTIVE'
                        order by o.name, w.name
                        """).param("userId", user.userId())
                .query((rs, rowNum) -> new WorkspaceOption((UUID) rs.getObject("organization_id"),
                        rs.getString("organization_name"), (UUID) rs.getObject("workspace_id"),
                        rs.getString("workspace_name"), rs.getString("description"))).list();
    }

    /**
     * 原子切换当前会话空间并签发新访问令牌；旧访问令牌因会话空间不再匹配而立即失效。
     *
     * @param workspaceId 目标空间
     * @return 新访问令牌与上下文
     */
    @Transactional
    public SwitchResult switchWorkspace(UUID workspaceId) {
        AuthenticatedUser user = RequestContext.require();
        MembershipRow membership = findMembership(user.userId(), workspaceId);
        jdbc.sql("""
                        update user_sessions set organization_id = :organizationId, workspace_id = :workspaceId
                        where id = :sessionId and user_id = :userId and revoked_at is null
                        """).param("organizationId", membership.organizationId()).param("workspaceId", membership.workspaceId())
                .param("sessionId", user.sessionId()).param("userId", user.userId()).update();
        Set<String> permissions = loadPermissions(user.userId(), membership.organizationId(), membership.workspaceId());
        String accessToken = jwtService.issueAccessToken(user.userId(), membership.organizationId(), membership.workspaceId(),
                user.sessionId(), user.tokenVersion());
        CurrentUser current = new CurrentUser(user.userId(), user.email(), user.displayName(), membership.organizationId(),
                membership.organizationName(), membership.workspaceId(), membership.workspaceName(), permissions);
        log.info("User switched workspace userId={} organizationId={} workspaceId={}", user.userId(),
                membership.organizationId(), membership.workspaceId());
        return new SwitchResult(accessToken, current);
    }

    /**
     * 按登录标识查询激活用户。目前标识持久化在唯一 email 字段中，因此同时兼容标准邮箱与开发账号；
     * 未命中和密码错误使用相同提示，避免暴露账号是否存在。
     */
    private UserRow findUserByIdentifier(String identifier) {
        return jdbc.sql("select id, email, password_hash, display_name, token_version from users where lower(email) = lower(:email) and status = 'ACTIVE'")
                .param("email", identifier.trim())
                .query((rs, rowNum) -> new UserRow((UUID) rs.getObject("id"), rs.getString("email"),
                        rs.getString("password_hash"), rs.getString("display_name"), rs.getLong("token_version")))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码错误"));
    }

    /** 查找用户可进入的空间，并同时验证组织与空间成员状态。 */
    private MembershipRow findMembership(UUID userId, UUID workspaceId) {
        String workspaceFilter = workspaceId == null ? "" : " and w.id = :workspaceId";
        JdbcClient.StatementSpec statement = jdbc.sql("""
                        select o.id as organization_id, o.name as organization_name,
                               w.id as workspace_id, w.name as workspace_name
                        from workspace_members wm
                        join workspaces w on w.id = wm.workspace_id and w.organization_id = wm.organization_id
                        join organizations o on o.id = w.organization_id
                        join organization_members om on om.organization_id = o.id and om.user_id = wm.user_id
                        where wm.user_id = :userId and wm.status = 'ACTIVE' and om.status = 'ACTIVE'
                          and w.status = 'ACTIVE' and o.status = 'ACTIVE'
                        """ + workspaceFilter + " order by wm.joined_at limit 1")
                .param("userId", userId);
        if (workspaceId != null) {
            statement = statement.param("workspaceId", workspaceId);
        }
        return statement.query((rs, rowNum) -> new MembershipRow(
                        (UUID) rs.getObject("organization_id"), rs.getString("organization_name"),
                        (UUID) rs.getObject("workspace_id"), rs.getString("workspace_name")))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "WORKSPACE_ACCESS_DENIED", "无权访问该知识空间"));
    }

    /** 查询 RBAC 权限并去重，系统角色和组织自定义角色共用同一关系表。 */
    private Set<String> loadPermissions(UUID userId, UUID organizationId, UUID workspaceId) {
        List<String> values = jdbc.sql("""
                        select distinct rp.permission_code
                        from workspace_member_roles wmr
                        join roles r on r.id = wmr.role_id
                        join role_permissions rp on rp.role_id = r.id
                        where wmr.organization_id = :organizationId and wmr.workspace_id = :workspaceId
                          and wmr.user_id = :userId
                          and (r.organization_id is null or r.organization_id = :organizationId)
                        """)
                .param("organizationId", organizationId)
                .param("workspaceId", workspaceId)
                .param("userId", userId)
                .query(String.class)
                .list();
        return Set.copyOf(values);
    }

    /** 将空或不规范 IP 转换成 PostgreSQL inet 可接受值。 */
    private String normalizeIp(String ip) {
        return ip == null || ip.isBlank() ? "0.0.0.0" : ip;
    }

    /** 映射带用户与租户信息的刷新会话。 */
    private static SessionRow mapSession(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SessionRow((UUID) rs.getObject("id"), (UUID) rs.getObject("user_id"),
                (UUID) rs.getObject("organization_id"), (UUID) rs.getObject("workspace_id"),
                rs.getTimestamp("expires_at").toInstant(), rs.getString("email"), rs.getString("display_name"),
                rs.getLong("token_version"), rs.getString("organization_name"), rs.getString("workspace_name"));
    }

    /** 将可空 SQL 时间转换为 Instant。 */
    private static Instant nullableInstant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * 将领域层 Instant 转成 PostgreSQL 驱动可明确识别的带时区参数；空值保持为空以支持可选过期时间。
     */
    private static OffsetDateTime sqlTimestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private record UserRow(UUID id, String email, String passwordHash, String displayName, long tokenVersion) { }
    private record MembershipRow(UUID organizationId, String organizationName, UUID workspaceId, String workspaceName) { }
    private record AuthenticatedRow(String email, String displayName, long tokenVersion) { }
    private record SessionRow(UUID id, UUID userId, UUID organizationId, UUID workspaceId, Instant expiresAt,
                              String email, String displayName, long tokenVersion,
                              String organizationName, String workspaceName) { }
    private record ApiKeyRow(UUID id, UUID userId, UUID organizationId, UUID workspaceId, String email,
                             String displayName, long tokenVersion) { }

    /** 登录或刷新成功后返回的服务层结果。 */
    public record LoginResult(String accessToken, String refreshToken, Instant refreshExpiresAt, CurrentUser user) { }

    /** 前端展示和授权决策所需的当前用户概要。 */
    public record CurrentUser(UUID id, String email, String displayName, UUID organizationId, String organizationName,
                              UUID workspaceId, String workspaceName, Set<String> permissions) { }
    /** 可切换的组织/空间。 */
    public record WorkspaceOption(UUID organizationId, String organizationName, UUID workspaceId,
                                  String workspaceName, String description) { }
    /** 空间切换结果。 */
    public record SwitchResult(String accessToken, CurrentUser user) { }
    /** 创建后只返回一次的 API Key。 */
    public record CreatedApiKey(UUID id, String name, String token, Instant expiresAt) { }
    /** 不含秘密的 API Key 元数据。 */
    public record ApiKeyView(UUID id, String name, String tokenPrefix, Instant expiresAt, Instant lastUsedAt,
                             Instant revokedAt, Instant createdAt) { }
}
