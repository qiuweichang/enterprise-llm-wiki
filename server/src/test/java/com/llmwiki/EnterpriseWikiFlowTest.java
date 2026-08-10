package com.llmwiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用真实 PostgreSQL 与 Redis 容器验证认证、直接新增、强制审核更新和原子发布主流程。
 * 当显式提供 LLM_WIKI_DB_PASSWORD 时复用开发基础设施并创建独立测试库，适配未启动 Docker 的环境。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EnterpriseWikiFlowTest {
    private static final String ADMIN_EMAIL = "admin@llm-wiki.test";
    private static final String ADMIN_PASSWORD = "TestPassword!2026";
    private static final boolean USE_EXTERNAL_SERVICES = hasText(System.getenv("LLM_WIKI_DB_PASSWORD"));
    private static final String TEST_DATABASE = "llm_wiki_test";

    /** 未提供外部数据库凭据时启用的隔离 PostgreSQL 容器。 */
    static final PostgreSQLContainer<?> POSTGRES = USE_EXTERNAL_SERVICES ? null
            : new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName(TEST_DATABASE)
                    .withUsername("postgres")
                    .withPassword("postgres");

    /** 未提供外部数据库凭据时与 PostgreSQL 配套启动的 Redis 容器。 */
    static final GenericContainer<?> REDIS = USE_EXTERNAL_SERVICES ? null
            : new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        if (USE_EXTERNAL_SERVICES) {
            ensureExternalTestDatabase();
        } else {
            POSTGRES.start();
            REDIS.start();
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DataSource dataSource;

    /**
     * 将动态容器连接、测试 JWT 和首个管理员引导配置注入应用。
     */
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        if (USE_EXTERNAL_SERVICES) {
            registry.add("spring.datasource.url", EnterpriseWikiFlowTest::externalTestDatabaseUrl);
            registry.add("spring.datasource.username", () -> environmentOr("LLM_WIKI_DB_USERNAME", "postgres"));
            registry.add("spring.datasource.password", () -> System.getenv("LLM_WIKI_DB_PASSWORD"));
            registry.add("spring.data.redis.host", () -> environmentOr("LLM_WIKI_REDIS_HOST", "127.0.0.1"));
            registry.add("spring.data.redis.port", () -> Integer.parseInt(environmentOr("LLM_WIKI_REDIS_PORT", "6379")));
            registry.add("spring.data.redis.database", () -> Integer.parseInt(environmentOr("LLM_WIKI_TEST_REDIS_DATABASE", "14")));
            registry.add("spring.data.redis.password", () -> environmentOr("LLM_WIKI_REDIS_PASSWORD", ""));
        } else {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            registry.add("spring.data.redis.host", REDIS::getHost);
            registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
            registry.add("spring.data.redis.password", () -> "");
        }
        registry.add("llm-wiki.jwt.secret", () -> "integration-test-jwt-secret-at-least-32-characters");
        registry.add("llm-wiki.bootstrap.enabled", () -> true);
        registry.add("llm-wiki.bootstrap.email", () -> ADMIN_EMAIL);
        registry.add("llm-wiki.bootstrap.password", () -> ADMIN_PASSWORD);
        registry.add("llm-wiki.worker.poll-delay", () -> "30s");
    }

    /**
     * 管理员可直接发布新页面，但更新同一页面必须先形成变更集，批准后修订号递增。
     */
    @Test
    void shouldRequireReviewForEveryExistingPageUpdate() throws Exception {
        String token = login().token();
        String title = "审核运行手册-" + UUID.randomUUID();
        JsonNode created = objectMapper.readTree(mockMvc.perform(post("/api/pages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","pageType":"TOPIC","contentMarkdown":"# %s\\n\\n初始发布内容。"}
                                """.formatted(title, title)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andReturn().getResponse().getContentAsString());
        String pageId = created.path("pageId").asText();

        JsonNode proposed = objectMapper.readTree(mockMvc.perform(post("/api/pages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pageId":"%s","title":"%s","pageType":"TOPIC","contentMarkdown":"# %s\\n\\n第二版必须审核。"}
                                """.formatted(pageId, title, title)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andReturn().getResponse().getContentAsString());
        String changeSetId = proposed.path("changeSetId").asText();

        mockMvc.perform(get("/api/pages/{pageId}", pageId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionNo").value(1))
                .andExpect(jsonPath("$.contentMarkdown").value(org.hamcrest.Matchers.containsString("初始发布")));

        mockMvc.perform(post("/api/reviews/{id}/approve", changeSetId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"内容与证据一致\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(get("/api/pages/{pageId}", pageId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.contentMarkdown").value(org.hamcrest.Matchers.containsString("第二版必须审核")));
    }

    /**
     * 创建第二个真实空间后，以最小权限运行角色直接查询数据库，验证第一空间页面不会跨 RLS 边界泄漏。
     */
    @Test
    void shouldEnforcePostgresRlsAcrossWorkspaceBoundary() throws Exception {
        LoginSession session = login();
        String title = "RLS隔离页面-" + UUID.randomUUID();
        mockMvc.perform(post("/api/pages")
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","pageType":"TOPIC","contentMarkdown":"# %s\\n\\n仅属于原始空间。"}
                                """.formatted(title, title)))
                .andExpect(status().isOk());

        JsonNode workspace = objectMapper.readTree(mockMvc.perform(post("/api/admin/workspaces")
                        .header("Authorization", "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"隔离空间-%s","description":"RLS integration test"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        UUID secondWorkspaceId = UUID.fromString(workspace.path("id").asText());

        assertThat(visiblePageCount(session.organizationId(), session.workspaceId())).isGreaterThan(0);
        assertThat(visiblePageCount(session.organizationId(), secondWorkspaceId)).isZero();
    }

    /** 创建真实会话并返回短期访问令牌及固定租户上下文。 */
    private LoginSession login() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(ADMIN_EMAIL, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode login = objectMapper.readTree(body);
        String token = login.path("accessToken").asText();
        assertThat(token).isNotBlank();
        return new LoginSession(token, UUID.fromString(login.path("user").path("organizationId").asText()),
                UUID.fromString(login.path("user").path("workspaceId").asText()));
    }

    /**
     * 在独立事务中降权为 llm_wiki_app 并统计当前 RLS 上下文可见页面，结束后回滚所有会话设置。
     */
    private int visiblePageCount(UUID organizationId, UUID workspaceId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement role = connection.createStatement()) {
                role.execute("set local role llm_wiki_app");
            }
            try (PreparedStatement context = connection.prepareStatement(
                    "select set_config('app.current_organization_id', ?, true), set_config('app.current_workspace_id', ?, true)")) {
                context.setString(1, organizationId.toString());
                context.setString(2, workspaceId.toString());
                context.execute();
            }
            try (Statement query = connection.createStatement();
                 ResultSet result = query.executeQuery("select count(*) from wiki_pages")) {
                result.next();
                int count = result.getInt(1);
                connection.rollback();
                return count;
            }
        }
    }

    /**
     * 在外部 PostgreSQL 实例上幂等创建专用测试库，绝不清理或复用正式 llm_wiki 库。
     */
    private static void ensureExternalTestDatabase() {
        String adminUrl = "jdbc:postgresql://%s:%s/postgres".formatted(
                environmentOr("LLM_WIKI_DB_HOST", "localhost"), environmentOr("LLM_WIKI_DB_PORT", "5432"));
        String username = environmentOr("LLM_WIKI_DB_USERNAME", "postgres");
        try (Connection connection = DriverManager.getConnection(adminUrl, username, System.getenv("LLM_WIKI_DB_PASSWORD"));
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select 1 from pg_database where datname = '" + TEST_DATABASE + "'")) {
            if (!result.next()) {
                result.close();
                statement.execute("create database " + TEST_DATABASE);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot prepare isolated PostgreSQL integration-test database", exception);
        }
    }

    /** 返回外部专用测试库 JDBC 地址。 */
    private static String externalTestDatabaseUrl() {
        return "jdbc:postgresql://%s:%s/%s".formatted(environmentOr("LLM_WIKI_DB_HOST", "localhost"),
                environmentOr("LLM_WIKI_DB_PORT", "5432"), TEST_DATABASE);
    }

    /** 读取非空环境变量，否则使用不含凭据的安全默认值。 */
    private static String environmentOr(String name, String fallback) {
        String value = System.getenv(name);
        return hasText(value) ? value : fallback;
    }

    /** 统一判定外部配置是否有效，避免空白值误启用外部测试模式。 */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 集成测试登录后需要复用的短期凭证与租户标识。 */
    private record LoginSession(String token, UUID organizationId, UUID workspaceId) { }
}
