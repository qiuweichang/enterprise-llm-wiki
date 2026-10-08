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
    @Autowired private com.llmwiki.query.SemanticIndexService semanticIndex;
    @Autowired private com.llmwiki.query.EmbeddingClient embeddings;
    @Autowired private com.llmwiki.query.QueryRetrievalService retrieval;
    @Autowired private com.llmwiki.query.QueryService queries;

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
        // dev 配置会规范化首个管理员；测试库必须与登录夹具使用同一身份，不能被默认账号 1 覆盖。
        registry.add("llm-wiki.development.account", () -> ADMIN_EMAIL);
        registry.add("llm-wiki.development.password", () -> ADMIN_PASSWORD);
        registry.add("llm-wiki.worker.poll-delay", () -> "30s");
        // 测试显式领取任务，以确定性验证失效令牌；生产调度器仍默认开启。
        registry.add("llm-wiki.semantic.worker-enabled", () -> "false");
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
        return new LoginSession(token, UUID.fromString(login.path("user").path("id").asText()), UUID.fromString(login.path("user").path("organizationId").asText()),
                UUID.fromString(login.path("user").path("workspaceId").asText()));
    }

    /** 真 PostgreSQL + 真 Python 向量：语义命中、审核隔离、过期租约、更新/归档和跨空间隔离。 */
    @Test
    void semanticIndexShouldFollowPublishedRevisionsAndTenantBoundary() throws Exception {
        var login=login();
        var auth=new com.llmwiki.security.AuthenticatedUser(login.userId(),login.organizationId(),login.workspaceId(),null,"","",0,java.util.Set.of());
        String title="差旅规则-"+UUID.randomUUID();
        String body="出差住宿费每晚最高报销五百元，须提供酒店发票。";
        JsonNode created=semanticPost("/api/pages",login.token(),java.util.Map.of("title",title,"pageType","TOPIC","contentMarkdown",body));
        UUID id=UUID.fromString(created.path("pageId").asText());
        String question="住旅馆花的钱公司给报多少";
        assertThat(retrieval.retrieve(auth,question)).extracting(com.llmwiki.query.QueryRetrievalService.RetrievedPage::id).doesNotContain(id);
        drainSemanticJobs();
        var vector=embeddings.embed(java.util.List.of(question),true).getFirst();
        assertThat(retrieval.retrieve(auth,question,vector,0.5)).extracting(com.llmwiki.query.QueryRetrievalService.RetrievedPage::id).contains(id);
        semanticIndex.settings(auth,false,0.5);
        var keywordOnly=queries.queryForUser(auth,question,question,"",false);
        assertThat(keywordOnly.retrievalMode()).isEqualTo("KEYWORD");
        assertThat(keywordOnly.citations()).extracting(com.llmwiki.query.QueryService.Citation::pageId).doesNotContain(id);
        semanticIndex.settings(auth,true,0.5);
        var hybrid=queries.queryForUser(auth,question,question,"",false);
        assertThat(hybrid.retrievalMode()).isEqualTo("HYBRID");
        assertThat(hybrid.citations()).extracting(com.llmwiki.query.QueryService.Citation::pageId).contains(id);
        String originalFingerprint=semanticIndex.snapshot(auth).fingerprint();

        // 提案未批准时，发布版本及其可查询向量不变。
        var proposal=semanticPost("/api/pages",login.token(),java.util.Map.of("pageId",id,"title",title,"pageType","TOPIC","contentMarkdown","苹果树需要修剪枝叶、及时浇水。"));
        assertThat(semanticIndex.snapshot(auth).fingerprint()).isEqualTo(originalFingerprint);
        assertThat(retrieval.retrieve(auth,question,vector,0.5)).extracting(com.llmwiki.query.QueryRetrievalService.RetrievedPage::id).contains(id);
        semanticPost("/api/reviews/"+proposal.path("changeSetId").asText()+"/approve",login.token(),java.util.Map.of("comment","语义版本测试"));
        assertThat(semanticIndex.snapshot(auth).fingerprint()).isNotEqualTo(originalFingerprint);
        assertThat(queries.queryForUser(auth,question,question,"",false).citations())
                .extracting(com.llmwiki.query.QueryService.Citation::pageId).doesNotContain(id);
        assertThat(retrieval.retrieve(auth,question,vector,0.5)).extracting(com.llmwiki.query.QueryRetrievalService.RetrievedPage::id).doesNotContain(id);

        // 重建改变 generation 和令牌，即使旧任务完成也不得覆盖新的任务。
        var stale=semanticIndex.claim().orElseThrow();
        var staleUser=new com.llmwiki.security.AuthenticatedUser(null,stale.org(),stale.workspace(),null,"","",0,java.util.Set.of());
        semanticIndex.rebuild(staleUser);
        assertThat(semanticIndex.complete(stale,java.util.List.of("过期结果"),java.util.List.of(vector))).isFalse();
        drainSemanticJobs();
        assertThat(retrieval.retrieve(auth,question,vector,0.5)).extracting(com.llmwiki.query.QueryRetrievalService.RetrievedPage::id).doesNotContain(id);

        var other=new com.llmwiki.security.AuthenticatedUser(null,login.organizationId(),UUID.randomUUID(),null,"","",0,java.util.Set.of());
        assertThat(retrieval.retrieve(other,"苹果",vector,0)).isEmpty();
        assertThat(semanticIndex.jobs(other)).isEmpty();

        var archived=semanticPost("/api/pages/"+id+"/archive",login.token(),java.util.Map.of());
        semanticPost("/api/reviews/"+archived.path("changeSetId").asText()+"/approve",login.token(),java.util.Map.of("comment","归档测试"));
        assertThat(retrieval.retrieve(auth,"苹果",vector,0)).extracting(com.llmwiki.query.QueryRetrievalService.RetrievedPage::id).doesNotContain(id);
    }

    /** 验证长文档尾部命中能传入回答上下文、失败状态保留并可通过重建恢复。 */
    @Test
    void semanticChunksShouldExposeTailAndRecoverFailedJobs() throws Exception {
        var login=login();
        var auth=new com.llmwiki.security.AuthenticatedUser(null,login.organizationId(),login.workspaceId(),null,"","",0,java.util.Set.of());
        var created=semanticPost("/api/pages",login.token(),java.util.Map.of("title","长文档-"+UUID.randomUUID(),"pageType","TOPIC",
                "contentMarkdown","苹果种植需要适量浇水。".repeat(650)+"\n出差住宿费每晚最高报销五百元，须提供酒店发票。"));
        UUID id=UUID.fromString(created.path("pageId").asText());
        var lease=semanticIndex.claim().orElseThrow();
        semanticIndex.fail(lease,new IllegalStateException("测试：Python 暂时不可用"));
        var leaseUser=new com.llmwiki.security.AuthenticatedUser(null,lease.org(),lease.workspace(),null,"","",0,java.util.Set.of());
        assertThat(semanticIndex.jobs(leaseUser)).anySatisfy(job->assertThat(job.get("lastError")).isEqualTo("测试：Python 暂时不可用"));
        semanticIndex.rebuild(leaseUser);
        drainSemanticJobs();
        var vector=embeddings.embed(java.util.List.of("住旅馆花的钱公司给报多少"),true).getFirst();
        assertThat(retrieval.retrieve(auth,"住旅馆花的钱公司给报多少",vector,0.5))
                .anySatisfy(page->{assertThat(page.id()).isEqualTo(id);assertThat(page.contentMarkdown()).contains("五百元").hasSizeLessThan(1000);});
        mockMvc.perform(get("/api/semantic")).andExpect(status().isUnauthorized());
    }

    /** 在隔离测试库通过正式接口创建提案/批准，不通过 SQL 绕过发布流程。 */
    private JsonNode semanticPost(String path,String token,Object payload) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post(path).header("Authorization","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(payload)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** 用真实模型排空有界测试数据集；任务提交使用与生产相同的原子提交方法。 */
    private void drainSemanticJobs() {
        for(int round=0;round<300;round++) {
            var candidate=semanticIndex.claim();
            if(candidate.isEmpty()) return;
            var lease=candidate.get();
            var chunks=com.llmwiki.query.EmbeddingClient.chunks(lease.title(),lease.markdown());
            java.util.List<java.util.List<Double>> vectors=new java.util.ArrayList<>();
            for(int i=0;i<chunks.size();i+=16) vectors.addAll(embeddings.embed(chunks.subList(i,Math.min(i+16,chunks.size())),false));
            assertThat(semanticIndex.complete(lease,chunks,vectors)).isTrue();
        }
        throw new AssertionError("测试任务未在限定次数内完成");
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
    private record LoginSession(String token, UUID userId, UUID organizationId, UUID workspaceId) { }
}
