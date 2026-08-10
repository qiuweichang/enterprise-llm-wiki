package com.llmwiki.admin;

import com.llmwiki.security.RequiresPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 暴露成员、角色、权限和空间管理 API。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService adminService;

    /** 创建管理控制器。 */
    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /** 列出成员。 */
    @GetMapping("/members")
    @RequiresPermission("MEMBER_MANAGE")
    public List<AdminService.MemberView> members() {
        return adminService.members();
    }

    /** 添加或加入成员。 */
    @PostMapping("/members")
    @RequiresPermission("MEMBER_MANAGE")
    public AdminService.MemberView addMember(@RequestBody AdminService.AddMemberRequest request) {
        return adminService.addMember(request);
    }

    /** 替换成员角色。 */
    @PutMapping("/members/{userId}/roles")
    @RequiresPermission("ROLE_MANAGE")
    public AdminService.MemberView updateMemberRoles(@PathVariable UUID userId, @RequestBody RoleCodesRequest request) {
        return adminService.updateMemberRoles(userId, request.roleCodes());
    }

    /** 列出角色。 */
    @GetMapping("/roles")
    @RequiresPermission("ROLE_MANAGE")
    public List<AdminService.RoleView> roles() {
        return adminService.roles();
    }

    /** 列出权限。 */
    @GetMapping("/permissions")
    @RequiresPermission("ROLE_MANAGE")
    public List<AdminService.PermissionView> permissions() {
        return adminService.permissions();
    }

    /** 创建自定义角色。 */
    @PostMapping("/roles")
    @RequiresPermission("ROLE_MANAGE")
    public AdminService.RoleView createRole(@RequestBody AdminService.RoleRequest request) {
        return adminService.createRole(request);
    }

    /** 更新自定义角色。 */
    @PutMapping("/roles/{roleId}")
    @RequiresPermission("ROLE_MANAGE")
    public AdminService.RoleView updateRole(@PathVariable UUID roleId, @RequestBody AdminService.RoleRequest request) {
        return adminService.updateRole(roleId, request);
    }

    /** 创建当前组织的新空间。 */
    @PostMapping("/workspaces")
    @RequiresPermission("MEMBER_MANAGE")
    public AdminService.WorkspaceCreated createWorkspace(@RequestBody AdminService.CreateWorkspaceRequest request) {
        return adminService.createWorkspace(request);
    }

    /** 成员角色替换请求。 */
    public record RoleCodesRequest(List<String> roleCodes) { }
}
