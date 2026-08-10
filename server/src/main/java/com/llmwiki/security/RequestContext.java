package com.llmwiki.security;

import com.llmwiki.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 以 ThreadLocal 承载当前请求身份；拦截器必须在请求结束时清理，防止容器线程复用导致串租户。
 */
public final class RequestContext {
    private static final ThreadLocal<AuthenticatedUser> CURRENT = new ThreadLocal<>();

    private RequestContext() {
    }

    /**
     * 设置当前请求身份，仅供认证拦截器使用。
     *
     * @param user 已验证身份
     */
    public static void set(AuthenticatedUser user) {
        CURRENT.set(user);
    }

    /**
     * 读取当前身份，缺失时拒绝继续执行。
     *
     * @return 当前请求身份
     */
    public static AuthenticatedUser require() {
        AuthenticatedUser user = CURRENT.get();
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
        }
        return user;
    }

    /**
     * 清理当前线程身份，必须在请求完成或异常退出时调用。
     */
    public static void clear() {
        CURRENT.remove();
    }
}

