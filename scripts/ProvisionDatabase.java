import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 通过 PostgreSQL JDBC 驱动幂等创建 LLM Wiki 数据库，供未安装 psql 或未启动 Docker 的开发机使用。
 */
public final class ProvisionDatabase {
    /** 数据库名只允许安全标识符字符，避免将动态名称直接拼接 SQL 时产生注入风险。 */
    private static final String SAFE_DATABASE_NAME = "[A-Za-z][A-Za-z0-9_]{0,62}";

    private ProvisionDatabase() {
    }

    /**
     * 连接 postgres 管理库并按需创建目标库。
     *
     * @param args 依次为主机、端口、用户名和目标数据库名；密码只从 LLM_WIKI_DB_PASSWORD 环境变量读取
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Expected arguments: host port username databaseName");
        }
        String password = System.getenv("LLM_WIKI_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("LLM_WIKI_DB_PASSWORD must be set");
        }
        String databaseName = args[3];
        if (!databaseName.matches(SAFE_DATABASE_NAME)) {
            throw new IllegalArgumentException("Database name contains unsupported characters");
        }
        String adminUrl = "jdbc:postgresql://%s:%s/postgres".formatted(args[0], args[1]);
        try (Connection connection = DriverManager.getConnection(adminUrl, args[2], password)) {
            if (databaseExists(connection, databaseName)) {
                System.out.println("PostgreSQL database '" + databaseName + "' already exists.");
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("create database \"" + databaseName + "\"");
            }
            System.out.println("Created PostgreSQL database '" + databaseName + "'.");
        }
    }

    /**
     * 使用参数化查询判断目标库是否存在。
     *
     * @param connection 已连接 postgres 管理库的连接
     * @param databaseName 待检查数据库名
     * @return 已存在时返回 true
     */
    private static boolean databaseExists(Connection connection, String databaseName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select 1 from pg_database where datname = ?")) {
            statement.setString(1, databaseName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }
}
