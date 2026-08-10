package com.llmwiki.security;

import com.llmwiki.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 读取控制器上的权限声明并执行 RBAC 判断；未声明权限的方法仍须先通过认证拦截器。
 */
@Component
public class AuthorizationInterceptor implements HandlerInterceptor {

    /**
     * 校验方法或控制器类声明的权限。
     *
     * @return 具备权限或目标不是控制器方法时为 true
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequiresPermission requirement = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequiresPermission.class);
        if (requirement == null) {
            requirement = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequiresPermission.class);
        }
        if (requirement != null && !RequestContext.require().hasPermission(requirement.value())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "当前角色缺少权限：" + requirement.value());
        }
        return true;
    }
}

