package com.llmwiki.bootstrap;

import com.llmwiki.config.LlmWikiProperties;
import com.llmwiki.security.PasswordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 仅在 dev profile 中把首个管理员归一为测试账号 1/1，避免将弱凭据逻辑带入生产运行时。
 */
@Component
@Profile("dev")
@Order(100)
public class DevelopmentCredentialService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DevelopmentCredentialService.class);
    /** 直接操作开发数据的数据库客户端；该服务不会在非 dev profile 注册。 */
    private final JdbcClient jdbc;
    /** 仍使用生产同款密码哈希，弱密码只影响测试便利性，不改变存储方式。 */
    private final PasswordService passwordService;
    /** 包含显式开发开关和账号密码的外部配置。 */
    private final LlmWikiProperties properties;

    /** 创建开发凭据归一服务。 */
    public DevelopmentCredentialService(JdbcClient jdbc, PasswordService passwordService,
                                        LlmWikiProperties properties) {
        this.jdbc = jdbc;
        this.passwordService = passwordService;
        this.properties = properties;
    }

    /**
     * 在首次引导之后设置开发管理员账号并撤销旧会话。
     *
     * @param args 应用启动参数，本流程不使用
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.development().allowInsecureCredentials()) {
            return;
        }
        String account = properties.development().account();
        String password = properties.development().password();
        if (account == null || account.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("Development account and password must be configured");
        }
        UUID existingAccountId = jdbc.sql("select id from users where lower(email) = lower(:account)")
                .param("account", account.trim()).query(UUID.class).optional().orElse(null);
        UUID administratorId = jdbc.sql("""
                            select u.id from users u
                            where exists (
                                select 1 from workspace_member_roles wmr
                                where wmr.user_id = u.id
                                  and wmr.role_id = '00000000-0000-0000-0000-000000000001'::uuid
                            )
                            order by (lower(u.email) = lower(:account)) desc, u.id limit 1
                            """).param("account", account.trim()).query(UUID.class).optional()
                .orElseThrow(() -> new IllegalStateException("No development administrator exists"));
        if (existingAccountId != null && !existingAccountId.equals(administratorId)) {
            throw new IllegalStateException("Development account is already owned by a non-administrator");
        }
        jdbc.sql("""
                        update users set email = :account, password_hash = :hash, display_name = '开发管理员',
                                         token_version = token_version + 1, updated_at = now()
                        where id = :userId
                        """).param("account", account.trim()).param("hash", passwordService.hash(password))
                .param("userId", administratorId).update();
        jdbc.sql("update user_sessions set revoked_at = now() where user_id = :userId and revoked_at is null")
                .param("userId", administratorId).update();
        log.info("Development administrator credentials normalized userId={} account={}",
                administratorId, account.trim());
    }
}
