package com.llmwiki.admin;

import com.llmwiki.audit.AuditService;
import com.llmwiki.common.ApiException;
import com.llmwiki.common.Slugifier;
import com.llmwiki.security.AuthenticatedUser;
import com.llmwiki.security.PasswordService;
import com.llmwiki.security.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 实现组织空间成员、角色和权限管理。
 * 所有非 RLS 管理表查询都显式包含 organization_id/workspace_id，防止管理接口跨租户读取。
 */
@Service
public class AdminService {
    private static final Logger log = LoggerFactory.getLogger(AdminService.class);
    private static final UUID ADMIN_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final JdbcClient jdbc;
    private final PasswordService passwordService;
    private final AuditService auditService;

    /** 创建管理服务。 */
    public AdminService(JdbcClient jdbc, PasswordService passwordService, AuditService auditService) {
        this.jdbc = jdbc;
        this.passwordService = passwordService;
        this.auditService = auditService;
    }

    /**
     * 列出当前空间成员和角色集合。
     */
    @Transactional(readOnly = true)
    public List<MemberView> members() {
        AuthenticatedUser actor = RequestContext.require();
        return jdbc.sql("""
                        select u.id, u.email, u.display_name, wm.status, wm.joined_at,
                               coalesce(array_agg(r.code order by r.code) filter (where r.code is not null), '{}') as roles
                        from workspace_members wm
                        join users u on u.id = wm.user_id
                        left join workspace_member_roles wmr on wmr.organization_id = wm.organization_id
                            and wmr.workspace_id = wm.workspace_id and wmr.user_id = wm.user_id
                        left join roles r on r.id = wmr.role_id
                        where wm.organization_id = :organizationId and wm.workspace_id = :workspaceId
                        group by u.id, wm.status, wm.joined_at
                        order by u.display_name
                        """).param("organizationId", actor.organizationId()).param("workspaceId", actor.workspaceId())
                .query(AdminService::mapMember).list();
    }

    /**
     * 创建或加入用户并原子授予空间角色。
     *
     * @param request 成员请求；新用户必须提供至少 12 位临时密码
     * @return 成员视图
     */
    @Transactional
    public MemberView addMember(AddMemberRequest request) {
        AuthenticatedUser actor = RequestContext.require();
        String email = required(request.email(), "邮箱不能为空").toLowerCase(Locale.ROOT);
        UUID userId = jdbc.sql("select id from users where lower(email) = :email")
                .param("email", email).query(UUID.class).optional().orElse(null);
        if (userId == null) {
            if (request.temporaryPassword() == null || request.temporaryPassword().length() < 12) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "TEMPORARY_PASSWORD_TOO_SHORT", "新用户临时密码至少 12 位");
            }
            userId = UUID.randomUUID();
            jdbc.sql("insert into users(id, email, password_hash, display_name) values (:id, :email, :hash, :name)")
                    .param("id", userId).param("email", email).param("hash", passwordService.hash(request.temporaryPassword()))
                    .param("name", required(request.displayName(), "姓名不能为空")).update();
        }
        jdbc.sql("""
                        insert into organization_members(organization_id, user_id, status)
                        values (:organizationId, :userId, 'ACTIVE')
                        on conflict (organization_id, user_id) do update set status = 'ACTIVE'
                        """).param("organizationId", actor.organizationId()).param("userId", userId).update();
        jdbc.sql("""
                        insert into workspace_members(organization_id, workspace_id, user_id, status)
                        values (:organizationId, :workspaceId, :userId, 'ACTIVE')
                        on conflict (workspace_id, user_id) do update set status = 'ACTIVE'
                        """).param("organizationId", actor.organizationId()).param("workspaceId", actor.workspaceId())
                .param("userId", userId).update();
        replaceRoles(userId, normalizeRoleCodes(request.roleCodes()), actor);
        auditService.record("MEMBER_ADDED", "USER", userId, null,
                java.util.Map.of("email", email, "roles", normalizeRoleCodes(request.roleCodes())), java.util.Map.of());
        log.info("Workspace member added userId={} workspaceId={} actorId={}", userId, actor.workspaceId(), actor.userId());
        return findMember(userId, actor);
    }

    /**
     * 替换成员角色，并保证空间至少保留一名组织管理员。
     *
     * @param userId 目标用户
     * @param roleCodes 新角色代码集合
     * @return 更新后的成员
     */
    @Transactional
    public MemberView updateMemberRoles(UUID userId, List<String> roleCodes) {
        AuthenticatedUser actor = RequestContext.require();
        boolean memberExists = jdbc.sql("""
                        select exists(select 1 from workspace_members where organization_id = :organizationId
                            and workspace_id = :workspaceId and user_id = :userId and status = 'ACTIVE')
                        """).param("organizationId", actor.organizationId()).param("workspaceId", actor.workspaceId())
                .param("userId", userId).query(Boolean.class).single();
        if (!memberExists) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "空间成员不存在");
        }
        List<String> normalized = normalizeRoleCodes(roleCodes);
        boolean removingAdmin = hasAdminRole(userId, actor) && !normalized.contains("ORG_ADMIN");
        if (removingAdmin && countAdmins(actor) <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "LAST_ADMIN_REQUIRED", "空间必须至少保留一名组织管理员");
        }
        replaceRoles(userId, normalized, actor);
        auditService.record("MEMBER_ROLES_UPDATED", "USER", userId, null,
                java.util.Map.of("roles", normalized), java.util.Map.of());
        log.info("Workspace member roles updated userId={} roles={} actorId={}", userId, normalized, actor.userId());
        return findMember(userId, actor);
    }

    /**
     * 列出系统角色、组织自定义角色和其权限集合。
     */
    @Transactional(readOnly = true)
    public List<RoleView> roles() {
        AuthenticatedUser actor = RequestContext.require();
        return jdbc.sql("""
                        select r.id, r.code, r.name, r.description, r.system_role,
                               coalesce(array_agg(rp.permission_code order by rp.permission_code)
                                        filter (where rp.permission_code is not null), '{}') as permissions
                        from roles r left join role_permissions rp on rp.role_id = r.id
                        where r.organization_id is null or r.organization_id = :organizationId
                        group by r.id order by r.system_role desc, r.name
                        """).param("organizationId", actor.organizationId()).query(AdminService::mapRole).list();
    }

    /**
     * 列出所有可分配权限代码。
     */
    @Transactional(readOnly = true)
    public List<PermissionView> permissions() {
        return jdbc.sql("select code, description from permissions order by code")
                .query((rs, rowNum) -> new PermissionView(rs.getString("code"), rs.getString("description"))).list();
    }

    /**
     * 创建当前组织自定义角色并校验每个权限代码存在。
     *
     * @param request 角色定义
     * @return 新角色
     */
    @Transactional
    public RoleView createRole(RoleRequest request) {
        AuthenticatedUser actor = RequestContext.require();
        String code = normalizeRoleCode(request.code());
        List<String> permissions = normalizePermissions(request.permissions());
        validatePermissions(permissions);
        UUID roleId = UUID.randomUUID();
        jdbc.sql("""
                        insert into roles(id, organization_id, code, name, description, system_role)
                        values (:id, :organizationId, :code, :name, :description, false)
                        """).param("id", roleId).param("organizationId", actor.organizationId()).param("code", code)
                .param("name", required(request.name(), "角色名称不能为空"))
                .param("description", request.description() == null ? "" : request.description().trim()).update();
        insertRolePermissions(roleId, permissions);
        auditService.record("ROLE_CREATED", "ROLE", roleId, null,
                java.util.Map.of("code", code, "permissions", permissions), java.util.Map.of());
        return findRole(roleId, actor);
    }

    /**
     * 更新组织自定义角色；系统角色不可修改，避免全局模板被租户污染。
     */
    @Transactional
    public RoleView updateRole(UUID roleId, RoleRequest request) {
        AuthenticatedUser actor = RequestContext.require();
        boolean owned = jdbc.sql("select exists(select 1 from roles where id = :id and organization_id = :organizationId and not system_role)")
                .param("id", roleId).param("organizationId", actor.organizationId()).query(Boolean.class).single();
        if (!owned) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SYSTEM_ROLE_IMMUTABLE", "系统角色不可修改");
        }
        List<String> permissions = normalizePermissions(request.permissions());
        validatePermissions(permissions);
        jdbc.sql("update roles set name = :name, description = :description where id = :id and organization_id = :organizationId")
                .param("name", required(request.name(), "角色名称不能为空"))
                .param("description", request.description() == null ? "" : request.description().trim())
                .param("id", roleId).param("organizationId", actor.organizationId()).update();
        jdbc.sql("delete from role_permissions where role_id = :roleId").param("roleId", roleId).update();
        insertRolePermissions(roleId, permissions);
        auditService.record("ROLE_UPDATED", "ROLE", roleId, null,
                java.util.Map.of("permissions", permissions), java.util.Map.of());
        return findRole(roleId, actor);
    }

    /**
     * 在当前组织创建新空间，并让创建者成为管理员。
     */
    @Transactional
    public WorkspaceCreated createWorkspace(CreateWorkspaceRequest request) {
        AuthenticatedUser actor = RequestContext.require();
        UUID workspaceId = UUID.randomUUID();
        String name = required(request.name(), "空间名称不能为空");
        String slug = Slugifier.slugify(request.slug() == null || request.slug().isBlank() ? name : request.slug());
        jdbc.sql("""
                        insert into workspaces(id, organization_id, slug, name, description, created_by)
                        values (:id, :organizationId, :slug, :name, :description, :userId)
                        """).param("id", workspaceId).param("organizationId", actor.organizationId()).param("slug", slug)
                .param("name", name).param("description", request.description() == null ? "" : request.description().trim())
                .param("userId", actor.userId()).update();
        jdbc.sql("insert into workspace_members(organization_id, workspace_id, user_id) values (:organizationId, :workspaceId, :userId)")
                .param("organizationId", actor.organizationId()).param("workspaceId", workspaceId)
                .param("userId", actor.userId()).update();
        jdbc.sql("""
                        insert into workspace_member_roles(organization_id, workspace_id, user_id, role_id, assigned_by)
                        values (:organizationId, :workspaceId, :userId, :roleId, :userId)
                        """).param("organizationId", actor.organizationId()).param("workspaceId", workspaceId)
                .param("userId", actor.userId()).param("roleId", ADMIN_ROLE_ID).update();
        auditService.record("WORKSPACE_CREATED", "WORKSPACE", workspaceId, null,
                java.util.Map.of("name", name, "slug", slug), java.util.Map.of());
        log.info("Workspace created workspaceId={} organizationId={} actorId={}", workspaceId,
                actor.organizationId(), actor.userId());
        return new WorkspaceCreated(workspaceId, slug, name);
    }

    /** 用稳定事务顺序替换目标成员全部角色。 */
    private void replaceRoles(UUID userId, List<String> roleCodes, AuthenticatedUser actor) {
        List<RoleIdCode> roles = jdbc.sql("""
                        select id, code from roles
                        where code in (:codes) and (organization_id is null or organization_id = :organizationId)
                        order by id
                        """).param("codes", roleCodes).param("organizationId", actor.organizationId())
                .query((rs, rowNum) -> new RoleIdCode((UUID) rs.getObject("id"), rs.getString("code"))).list();
        if (roles.size() != Set.copyOf(roleCodes).size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "包含不存在或不属于当前组织的角色");
        }
        jdbc.sql("delete from workspace_member_roles where organization_id = :organizationId and workspace_id = :workspaceId and user_id = :userId")
                .param("organizationId", actor.organizationId()).param("workspaceId", actor.workspaceId())
                .param("userId", userId).update();
        for (RoleIdCode role : roles) {
            jdbc.sql("""
                            insert into workspace_member_roles(organization_id, workspace_id, user_id, role_id, assigned_by)
                            values (:organizationId, :workspaceId, :userId, :roleId, :actorId)
                            """).param("organizationId", actor.organizationId()).param("workspaceId", actor.workspaceId())
                    .param("userId", userId).param("roleId", role.id()).param("actorId", actor.userId()).update();
        }
    }

    /** 插入角色权限关系。 */
    private void insertRolePermissions(UUID roleId, List<String> permissions) {
        for (String permission : permissions) {
            jdbc.sql("insert into role_permissions(role_id, permission_code) values (:roleId, :permission)")
                    .param("roleId", roleId).param("permission", permission).update();
        }
    }

    /** 验证所有权限代码存在。 */
    private void validatePermissions(List<String> permissions) {
        long count = jdbc.sql("select count(*) from permissions where code in (:codes)")
                .param("codes", permissions).query(Long.class).single();
        if (count != permissions.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PERMISSION", "包含不存在的权限代码");
        }
    }

    /** 查找成员视图。 */
    private MemberView findMember(UUID userId, AuthenticatedUser actor) {
        return jdbc.sql("""
                        select u.id, u.email, u.display_name, wm.status, wm.joined_at,
                               coalesce(array_agg(r.code order by r.code) filter (where r.code is not null), '{}') as roles
                        from workspace_members wm join users u on u.id = wm.user_id
                        left join workspace_member_roles wmr on wmr.organization_id = wm.organization_id
                            and wmr.workspace_id = wm.workspace_id and wmr.user_id = wm.user_id
                        left join roles r on r.id = wmr.role_id
                        where wm.organization_id = :organizationId and wm.workspace_id = :workspaceId and wm.user_id = :userId
                        group by u.id, wm.status, wm.joined_at
                        """).param("organizationId", actor.organizationId()).param("workspaceId", actor.workspaceId())
                .param("userId", userId).query(AdminService::mapMember).single();
    }

    /** 查找角色视图。 */
    private RoleView findRole(UUID roleId, AuthenticatedUser actor) {
        return jdbc.sql("""
                        select r.id, r.code, r.name, r.description, r.system_role,
                               coalesce(array_agg(rp.permission_code order by rp.permission_code)
                                        filter (where rp.permission_code is not null), '{}') as permissions
                        from roles r left join role_permissions rp on rp.role_id = r.id
                        where r.id = :id and (r.organization_id is null or r.organization_id = :organizationId)
                        group by r.id
                        """).param("id", roleId).param("organizationId", actor.organizationId())
                .query(AdminService::mapRole).single();
    }

    /** 判断成员是否具有组织管理员角色。 */
    private boolean hasAdminRole(UUID userId, AuthenticatedUser actor) {
        return jdbc.sql("""
                        select exists(select 1 from workspace_member_roles where organization_id = :organizationId
                            and workspace_id = :workspaceId and user_id = :userId and role_id = :roleId)
                        """).param("organizationId", actor.organizationId()).param("workspaceId", actor.workspaceId())
                .param("userId", userId).param("roleId", ADMIN_ROLE_ID).query(Boolean.class).single();
    }

    /** 统计当前空间激活管理员数量。 */
    private long countAdmins(AuthenticatedUser actor) {
        return jdbc.sql("""
                        select count(*) from workspace_member_roles wmr
                        join workspace_members wm on wm.workspace_id = wmr.workspace_id and wm.user_id = wmr.user_id
                        where wmr.organization_id = :organizationId and wmr.workspace_id = :workspaceId
                          and wmr.role_id = :roleId and wm.status = 'ACTIVE'
                        """).param("organizationId", actor.organizationId()).param("workspaceId", actor.workspaceId())
                .param("roleId", ADMIN_ROLE_ID).query(Long.class).single();
    }

    /** 规范角色代码列表，空值默认贡献者。 */
    private List<String> normalizeRoleCodes(List<String> values) {
        List<String> result = values == null || values.isEmpty() ? List.of("CONTRIBUTOR") : values.stream()
                .filter(value -> value != null && !value.isBlank()).map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct().sorted().toList();
        if (result.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROLE_REQUIRED", "成员至少需要一个角色");
        }
        return result;
    }

    /** 规范自定义角色代码。 */
    private String normalizeRoleCode(String value) {
        String code = required(value, "角色代码不能为空").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
        return code.startsWith("CUSTOM_") ? code : "CUSTOM_" + code;
    }

    /** 规范权限列表。 */
    private List<String> normalizePermissions(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PERMISSION_REQUIRED", "角色至少需要一个权限");
        }
        return values.stream().map(value -> value.trim().toUpperCase(Locale.ROOT)).distinct().sorted().toList();
    }

    /** 校验必填文本。 */
    private String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
        }
        return value.trim();
    }

    /** 映射成员及 PostgreSQL 文本数组。 */
    private static MemberView mapMember(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String[] roles = (String[]) rs.getArray("roles").getArray();
        return new MemberView((UUID) rs.getObject("id"), rs.getString("email"), rs.getString("display_name"),
                rs.getString("status"), Arrays.asList(roles), rs.getTimestamp("joined_at").toInstant());
    }

    /** 映射角色及权限数组。 */
    private static RoleView mapRole(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String[] permissions = (String[]) rs.getArray("permissions").getArray();
        return new RoleView((UUID) rs.getObject("id"), rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getBoolean("system_role"), Arrays.asList(permissions));
    }

    private record RoleIdCode(UUID id, String code) { }
    /** 成员列表视图。 */
    public record MemberView(UUID id, String email, String displayName, String status, List<String> roles,
                             Instant joinedAt) { }
    /** 添加成员请求。 */
    public record AddMemberRequest(String email, String displayName, String temporaryPassword, List<String> roleCodes) { }
    /** 角色视图。 */
    public record RoleView(UUID id, String code, String name, String description, boolean systemRole,
                           List<String> permissions) { }
    /** 权限视图。 */
    public record PermissionView(String code, String description) { }
    /** 自定义角色请求。 */
    public record RoleRequest(String code, String name, String description, List<String> permissions) { }
    /** 创建空间请求。 */
    public record CreateWorkspaceRequest(String slug, String name, String description) { }
    /** 新空间概要。 */
    public record WorkspaceCreated(UUID id, String slug, String name) { }
}

