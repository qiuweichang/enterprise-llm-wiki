package com.llmwiki.security;

import java.util.Set;
import java.util.UUID;

/**
 * 一次已认证请求的不可变安全上下文，绑定用户、组织、空间、会话和权限集合。
 */
public record AuthenticatedUser(
        UUID userId,
        UUID organizationId,
        UUID workspaceId,
        UUID sessionId,
        String email,
        String displayName,
        long tokenVersion,
        Set<String> permissions
) {
    /**
     * 判断当前用户是否拥有指定权限。
     *
     * @param permission 权限代码
     * @return 拥有权限时为 true
     */
    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}

