package com.llmwiki.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明控制器方法所需的 RBAC 权限，由自定义 MVC 拦截器执行，不依赖 Spring Security。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {
    /** @return 必须具备的权限代码 */
    String value();
}

