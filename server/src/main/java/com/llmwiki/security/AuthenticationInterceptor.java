package com.llmwiki.security;

import com.llmwiki.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 自定义 Bearer 认证拦截器：验证 JWT 后再次校验数据库会话，并确保请求结束时清理身份。
 */
@Component
public class AuthenticationInterceptor implements HandlerInterceptor {
    private final JwtService jwtService;
    private final AuthService authService;

    /**
     * 创建认证拦截器。
     *
     * @param jwtService JWT 校验服务
     * @param authService 数据库会话校验服务
     */
    public AuthenticationInterceptor(JwtService jwtService, AuthService authService) {
        this.jwtService = jwtService;
        this.authService = authService;
    }

    /**
     * 在控制器执行前建立已验证请求上下文。
     *
     * @return 认证通过时为 true
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "缺少 Bearer 登录凭证");
        }
        String token = authorization.substring(7);
        if (token.startsWith("lwk_")) {
            RequestContext.set(authService.authenticateApiKey(token));
        } else {
            JwtService.TokenClaims claims = jwtService.verifyAccessToken(token);
            RequestContext.set(authService.authenticate(claims));
        }
        return true;
    }

    /**
     * 无论控制器是否异常，都清除 ThreadLocal 防止线程复用造成身份泄漏。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        RequestContext.clear();
    }
}
