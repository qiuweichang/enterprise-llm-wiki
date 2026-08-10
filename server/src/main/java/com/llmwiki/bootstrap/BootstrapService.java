package com.llmwiki.bootstrap;

import com.llmwiki.common.ContentHash;
import com.llmwiki.config.LlmWikiProperties;
import com.llmwiki.security.PasswordService;
import com.llmwiki.security.TenantDatabaseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 在显式开启时原子创建首个组织、空间、管理员和基础 Wiki 页面。
 * PostgreSQL 事务级 advisory lock 确保多实例同时启动时只执行一次。
 */
@Component
@Order(0)
public class BootstrapService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapService.class);
    private static final UUID ADMIN_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final JdbcClient jdbc;
    private final PasswordService passwordService;
    private final TenantDatabaseContext tenantDatabaseContext;
    private final LlmWikiProperties properties;

    /**
     * 创建首次引导服务。
     */
    public BootstrapService(JdbcClient jdbc, PasswordService passwordService,
                            TenantDatabaseContext tenantDatabaseContext, LlmWikiProperties properties) {
        this.jdbc = jdbc;
        this.passwordService = passwordService;
        this.tenantDatabaseContext = tenantDatabaseContext;
        this.properties = properties;
    }

    /**
     * 应用启动后按配置执行一次事务化引导。
     *
     * @param args 启动参数，本流程不使用
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.bootstrap().enabled()) {
            log.info("Initial administrator bootstrap is disabled");
            return;
        }
        jdbc.sql("select pg_advisory_xact_lock(hashtext('llm-wiki-bootstrap'))").query().singleRow();
        long users = jdbc.sql("select count(*) from users").query(Long.class).single();
        if (users > 0) {
            log.info("Initial administrator bootstrap skipped because users already exist");
            return;
        }
        String password = properties.bootstrap().password();
        boolean insecureDevelopment = properties.development().allowInsecureCredentials();
        if (password == null || (!insecureDevelopment && password.length() < 12)) {
            throw new IllegalStateException("LLM_WIKI_BOOTSTRAP_PASSWORD must contain at least 12 characters when bootstrap is enabled");
        }
        UUID organizationId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        jdbc.sql("insert into organizations(id, slug, name) values (:id, 'enterprise', '企业知识中心')")
                .param("id", organizationId).update();
        jdbc.sql("insert into users(id, email, password_hash, display_name) values (:id, :email, :hash, '管理员')")
                .param("id", userId).param("email", properties.bootstrap().email().trim().toLowerCase())
                .param("hash", passwordService.hash(password)).update();
        jdbc.sql("insert into organization_members(organization_id, user_id) values (:organizationId, :userId)")
                .param("organizationId", organizationId).param("userId", userId).update();
        jdbc.sql("""
                        insert into workspaces(id, organization_id, slug, name, description, created_by)
                        values (:id, :organizationId, 'enterprise-wiki', '企业知识库',
                                '由多位成员共同提供资料、由 AI 持续编译和维护的可审核知识 Wiki', :userId)
                        """)
                .param("id", workspaceId).param("organizationId", organizationId).param("userId", userId).update();
        jdbc.sql("insert into workspace_members(organization_id, workspace_id, user_id) values (:organizationId, :workspaceId, :userId)")
                .param("organizationId", organizationId).param("workspaceId", workspaceId).param("userId", userId).update();
        jdbc.sql("""
                        insert into workspace_member_roles(organization_id, workspace_id, user_id, role_id, assigned_by)
                        values (:organizationId, :workspaceId, :userId, :roleId, :userId)
                        """)
                .param("organizationId", organizationId).param("workspaceId", workspaceId)
                .param("userId", userId).param("roleId", ADMIN_ROLE_ID).update();

        tenantDatabaseContext.apply(organizationId, workspaceId);
        List<SeedPage> pages = List.of(
                new SeedPage("README", "知识库说明", "README", """
                        # 企业知识库

                        这是团队共享的、由 AI 持续编译并由成员审核的企业知识 Wiki。

                        ## 工作原则

                        - 原始来源保持不可变，所有结论都关联证据。
                        - 已有知识的任何更新都必须进入审核队列。
                        - 新页面是否直接发布由成员权限决定。
                        - 查询优先读取已编译 Wiki，而不是每次重新解释所有原始资料。
                        """),
                new SeedPage("CONTEXT", "当前上下文", "CONTEXT", """
                        # 当前上下文

                        ## 当前目标

                        建立可追溯、可审核、可持续演进的企业知识体系。

                        ## 待办

                        - 添加第一份企业资料。
                        - 审核 AI 提出的知识变更。
                        - 邀请贡献者与审核员加入空间。
                        """),
                new SeedPage("开始使用", "开始使用 LLM Wiki", "TOPIC", """
                        # 开始使用 LLM Wiki

                        通过“资料来源”添加文本、网页或文件。后台服务会提取内容、生成候选知识变更，并根据权限自动发布新页面或送审。

                        对已有页面的修改永远不会绕过审核。批准后会生成不可变修订，并保留变更人、证据和审计记录。
                        """)
        );
        for (SeedPage page : pages) {
            insertSeedPage(organizationId, workspaceId, userId, page);
        }
        log.info("Initial enterprise workspace bootstrapped organizationId={} workspaceId={} adminUserId={}",
                organizationId, workspaceId, userId);
    }

    /**
     * 插入一页初始知识及其首个不可变修订。
     */
    private void insertSeedPage(UUID organizationId, UUID workspaceId, UUID userId, SeedPage seed) {
        UUID pageId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        String hash = ContentHash.sha256(seed.content());
        jdbc.sql("""
                        insert into wiki_pages(id, organization_id, workspace_id, slug, title, page_type,
                                               revision_no, content_markdown, content_hash, created_by, updated_by)
                        values (:id, :organizationId, :workspaceId, :slug, :title, :pageType,
                                1, :content, :hash, :userId, :userId)
                        """)
                .param("id", pageId).param("organizationId", organizationId).param("workspaceId", workspaceId)
                .param("slug", seed.slug()).param("title", seed.title()).param("pageType", seed.pageType())
                .param("content", seed.content()).param("hash", hash).param("userId", userId).update();
        jdbc.sql("""
                        insert into wiki_page_revisions(id, organization_id, workspace_id, page_id, revision_no,
                                                        title, content_markdown, content_hash, change_summary, published_by)
                        values (:id, :organizationId, :workspaceId, :pageId, 1, :title, :content, :hash,
                                'Initial workspace bootstrap', :userId)
                        """)
                .param("id", revisionId).param("organizationId", organizationId).param("workspaceId", workspaceId)
                .param("pageId", pageId).param("title", seed.title()).param("content", seed.content())
                .param("hash", hash).param("userId", userId).update();
        jdbc.sql("update wiki_pages set current_revision_id = :revisionId where id = :pageId")
                .param("revisionId", revisionId).param("pageId", pageId).update();
    }

    /** 初始页面的最小描述。 */
    private record SeedPage(String slug, String title, String pageType, String content) { }
}
