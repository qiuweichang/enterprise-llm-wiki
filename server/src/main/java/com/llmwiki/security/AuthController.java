package com.llmwiki.security;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.UUID;

/**
 * 提供登录、刷新、退出与当前用户接口，刷新凭证只保存在 HttpOnly Cookie 中。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final String REFRESH_COOKIE = "llm_wiki_refresh";
    private final AuthService authService;

    /**
     * 创建认证控制器。
     *
     * @param authService 认证服务
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 校验凭证并创建会话。
     *
     * @param body 登录请求
     * @param request HTTP 请求
     * @param response HTTP 响应，用于写入刷新 Cookie
     * @return 访问令牌与当前用户信息
     */
    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest body,
                                     HttpServletRequest request, HttpServletResponse response) {
        AuthService.LoginResult result = authService.login(body.identifier(), body.password(), body.workspaceId(), request);
        writeRefreshCookie(response, result.refreshToken(), result.refreshExpiresAt().isAfter(java.time.Instant.now())
                ? Duration.between(java.time.Instant.now(), result.refreshExpiresAt()) : Duration.ZERO);
        return Map.of("accessToken", result.accessToken(), "user", result.user());
    }

    /**
     * 轮换刷新凭证并签发新的访问令牌。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @return 新访问令牌与当前用户信息
     */
    @PostMapping("/refresh")
    public Map<String, Object> refresh(HttpServletRequest request, HttpServletResponse response) {
        String token = readCookie(request, REFRESH_COOKIE);
        AuthService.LoginResult result = authService.refresh(token);
        writeRefreshCookie(response, result.refreshToken(), Duration.between(java.time.Instant.now(), result.refreshExpiresAt()));
        return Map.of("accessToken", result.accessToken(), "user", result.user());
    }

    /**
     * 撤销当前会话并清除刷新 Cookie。
     */
    @PostMapping("/logout")
    public void logout(HttpServletResponse response) {
        authService.logout();
        writeRefreshCookie(response, "", Duration.ZERO);
    }

    /**
     * 返回当前登录用户、组织、空间和权限集合。
     *
     * @return 当前用户信息
     */
    @GetMapping("/me")
    public AuthService.CurrentUser me() {
        return authService.currentUser();
    }

    /**
     * 列出当前用户可访问的组织与空间。
     */
    @GetMapping("/workspaces")
    public List<AuthService.WorkspaceOption> workspaces() {
        return authService.workspaces();
    }

    /**
     * 切换当前会话的组织/空间上下文并返回新访问令牌。
     */
    @PostMapping("/switch-workspace")
    public AuthService.SwitchResult switchWorkspace(@RequestBody SwitchWorkspaceRequest request) {
        return authService.switchWorkspace(request.workspaceId());
    }

    /** 创建绑定当前空间的 MCP API Key，原始令牌只返回一次。 */
    @PostMapping("/api-keys")
    @RequiresPermission("MCP_USE")
    public AuthService.CreatedApiKey createApiKey(@RequestBody CreateApiKeyRequest request) {
        return authService.createApiKey(request.name(), request.expiresAt());
    }

    /** 列出当前用户的 MCP API Key 元数据。 */
    @GetMapping("/api-keys")
    @RequiresPermission("MCP_USE")
    public List<AuthService.ApiKeyView> apiKeys() {
        return authService.apiKeys();
    }

    /** 撤销 API Key。 */
    @PostMapping("/api-keys/{keyId}/revoke")
    @RequiresPermission("MCP_USE")
    public void revokeApiKey(@org.springframework.web.bind.annotation.PathVariable UUID keyId) {
        authService.revokeApiKey(keyId);
    }

    /** 写入安全属性完整的刷新 Cookie。 */
    private void writeRefreshCookie(HttpServletResponse response, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(false)
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(maxAge)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    /** 从请求 Cookie 中读取指定值。 */
    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies).filter(cookie -> name.equals(cookie.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
    }

    /**
     * 登录请求载荷。identifier 可填写企业邮箱或由组织约定的登录账号；开发 profile 使用账号 1。
     */
    public record LoginRequest(@JsonAlias("email") @NotBlank String identifier,
                               @NotBlank String password, UUID workspaceId) { }
    /** 空间切换请求。 */
    public record SwitchWorkspaceRequest(UUID workspaceId) { }
    /** 创建 API Key 请求。 */
    public record CreateApiKeyRequest(String name, java.time.Instant expiresAt) { }
}
