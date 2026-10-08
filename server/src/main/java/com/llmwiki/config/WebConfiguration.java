package com.llmwiki.config;

import com.llmwiki.security.AuthenticationInterceptor;
import com.llmwiki.security.AuthorizationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 配置无 Spring Security 的认证/授权拦截链与本地开发跨域策略。
 */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {
    private final AuthenticationInterceptor authenticationInterceptor;
    private final AuthorizationInterceptor authorizationInterceptor;

    /**
     * 创建 Web 配置。
     *
     * @param authenticationInterceptor 自定义认证拦截器
     * @param authorizationInterceptor 自定义 RBAC 拦截器
     */
    public WebConfiguration(AuthenticationInterceptor authenticationInterceptor,
                            AuthorizationInterceptor authorizationInterceptor) {
        this.authenticationInterceptor = authenticationInterceptor;
        this.authorizationInterceptor = authorizationInterceptor;
    }

    /**
     * 注册拦截器顺序：先认证建立上下文，再执行权限判断。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticationInterceptor)
                .addPathPatterns("/api/**", "/mcp")
                .excludePathPatterns("/api/auth/login", "/api/auth/refresh");
        registry.addInterceptor(authorizationInterceptor)
                .addPathPatterns("/api/**", "/mcp")
                .excludePathPatterns("/api/auth/login", "/api/auth/refresh");
    }

    /**
     * 允许本地 Vite 开发服务器访问 API；生产环境由同源反向代理部署。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // 5175 用于本地并行联调，避免占用个人网站的 5173；不允许任意来源或通配符。
                .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173",
                        "http://localhost:5175", "http://127.0.0.1:5175")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
