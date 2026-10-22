package com.iblog.module.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库自举 + 自检（在 Spring 容器启动之前执行）。
 *
 * <p>为什么放在 {@link ApplicationEnvironmentPreparedEvent}：这一刻配置文件（含 profile）已经解析完毕，
 * 但 DataSource、MyBatis 都还没创建，所以「库不存在」不会变成几百行堆栈，而是被这里拦下来处理好。</p>
 *
 * <p>行为：</p>
 * <ol>
 *   <li>{@code iblog.db.auto-init=true}（开发环境默认）：库不存在则建库，表不存在则建表并写入种子数据；</li>
 *   <li>{@code iblog.db.auto-init=false}（生产环境默认）：只做自检，缺库缺表时给出可操作的中文提示；</li>
 *   <li>自检不通过会抛出带提示块的异常，直接中止启动，避免带着半残状态跑起来。</li>
 * </ol>
 */
public class DatabaseBootstrap implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Logger log = LoggerFactory.getLogger(DatabaseBootstrap.class);

    /** 表结构脚本（classpath）。 */
    private static final String SCHEMA_SCRIPT = "db/01-schema.sql";
    /** 种子数据脚本（classpath，可重复执行）。 */
    private static final String DATA_SCRIPT = "db/02-data.sql";
    /**
     * 增量迁移脚本（classpath，全部幂等）。
     *
     * <p>为什么需要这一段：下面的哨兵判断只看 {@code sys_user} 在不在，只要在就认为「表已经建好」，
     * 于是新加的模块（美食、行情）在**已有库**上永远不会被自动创建 —— 表现为启动成功、
     * 但一访问新功能就报 Table doesn't exist。所以这里把迁移脚本也跑一遍：
     * 它们全是 CREATE TABLE IF NOT EXISTS / 条件 ALTER，重复执行没有副作用。</p>
     */
    private static final String[] MIGRATION_SCRIPTS = {
            "db/03-migration-food.sql",
            "db/04-migration-stock.sql",
            "db/05-migration-paper.sql",
            "db/06-migration-paper-multi.sql",
    };
    /** 用于判断「表是否已建好」的哨兵表。 */
    private static final String SENTINEL_TABLE = "sys_user";
    /** MySQL 「库不存在」的错误码。 */
    private static final int ERR_BAD_DB = 1049;

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();

        String url = environment.getProperty("spring.datasource.url");
        if (url == null || !url.startsWith("jdbc:mysql:")) {
            return; // 非 MySQL 数据源，不自作主张
        }
        boolean autoInit = environment.getProperty("iblog.db.auto-init", Boolean.class, Boolean.TRUE);
        String username = environment.getProperty("spring.datasource.username", "root");
        String password = environment.getProperty("spring.datasource.password", "");

        try {
            ensureReady(url, username, password, autoInit);
        } catch (SQLException e) {
            throw new IllegalStateException(translate(e), e);
        }
    }

    private void ensureReady(String url, String username, String password, boolean autoInit) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            if (!tableExists(connection)) {
                if (!autoInit) {
                    throw new IllegalStateException(hint(
                            "数据库已连上，但里面还没有 iblog 的表结构。",
                            "请执行初始化脚本：",
                            "  mysql -uroot -p -e \"CREATE DATABASE IF NOT EXISTS iblog DEFAULT CHARACTER SET utf8mb4;\"",
                            "  mysql -uroot -p iblog < iblog-server/src/main/resources/db/01-schema.sql",
                            "  mysql -uroot -p iblog < iblog-server/src/main/resources/db/02-data.sql",
                            "或临时打开自动建表：iblog.db.auto-init=true"));
                }
                initSchema(url, username, password);
                log.info("{}", hint("检测到数据库还是空的，已自动建表并写入种子数据。"));
            } else {
                // 已有库：哨兵表在，只补跑增量迁移（幂等），不做种子数据。
                applyMigrations(connection);
            }
        } catch (SQLException e) {
            String database = databaseName(url);
            if (!autoInit || database == null || !isUnknownDatabase(e)) {
                throw e;
            }
            createDatabase(url, database, username, password);
            initSchema(url, username, password);
            log.info("{}", hint("首次启动：已自动创建数据库 `" + database + "` 并完成初始化。"));
        }
    }

    /**
     * 对已有库补跑增量迁移脚本。
     *
     * <p><b>失败为什么只告警、不中止启动</b>：本博客老模块（文章/分类/评论）不依赖这些新表，
     * 一旦某个迁移脚本写错就让整站起不来，等于为了还没人用的新功能赔上已有的全部功能 ——
     * 线上那次「新功能报错」远比「整站 502」更好处理。所以这里一律降级为 ERROR 日志，
     * 让运维能从日志里看到，而不是拿可用性去换启动检查的严格性。</p>
     *
     * <p>脚本本身全部幂等（CREATE TABLE IF NOT EXISTS / 条件 ALTER），每次启动重跑没有副作用。</p>
     */
    private void applyMigrations(Connection connection) {
        for (String script : MIGRATION_SCRIPTS) {
            ClassPathResource resource = new ClassPathResource(script);
            if (!resource.exists()) {
                continue;
            }
            try {
                ScriptUtils.executeSqlScript(connection, resource);
            } catch (Exception e) {
                log.error("增量迁移脚本 {} 执行失败，相关新功能可能不可用。"
                                + "请手工执行后重启：mysql -uroot -p iblog < iblog-server/src/main/resources/{}",
                        script, script, e);
            }
        }
    }

    /** 判断异常是否是「库不存在」。 */
    private static boolean isUnknownDatabase(SQLException e) {
        return e.getErrorCode() == ERR_BAD_DB
                || (e.getMessage() != null && e.getMessage().contains("Unknown database"));
    }

    /** 建库（用 URL 去掉库名后的地址连到 MySQL 实例上执行）。 */
    private void createDatabase(String url, String database, String username, String password) throws SQLException {
        String sql = "CREATE DATABASE IF NOT EXISTS `" + database + "` "
                + "DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci";
        try (Connection connection = DriverManager.getConnection(serverUrl(url), username, password);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** 建表 + 种子数据，两个脚本都是幂等的。 */
    private void initSchema(String url, String username, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(SCHEMA_SCRIPT));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(DATA_SCRIPT));
        }
    }

    private boolean tableExists(Connection connection) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = '" + SENTINEL_TABLE + "'";
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    /**
     * 从 JDBC URL 里取库名，取不到返回 {@code null}。
     * 例：{@code jdbc:mysql://localhost:3306/iblog?useSSL=false} → {@code iblog}
     */
    static String databaseName(String url) {
        int pathStart = pathStart(url);
        if (pathStart < 0) {
            return null;
        }
        String path = url.substring(pathStart + 1);
        int queryStart = path.indexOf('?');
        if (queryStart >= 0) {
            path = path.substring(0, queryStart);
        }
        path = path.trim();
        return path.isEmpty() ? null : path;
    }

    /**
     * 拆出「不带库名」的 URL，用来连到 MySQL 实例本身（建库用）。
     * 例：{@code jdbc:mysql://localhost:3306/iblog?useSSL=false} → {@code jdbc:mysql://localhost:3306/?useSSL=false}
     */
    static String serverUrl(String url) {
        int pathStart = pathStart(url);
        if (pathStart < 0) {
            return url;
        }
        String tail = url.substring(pathStart + 1);
        int queryStart = tail.indexOf('?');
        String query = queryStart >= 0 ? tail.substring(queryStart) : "";
        return url.substring(0, pathStart + 1) + query;
    }

    /** 返回 {@code //host:port} 之后第一个 {@code /} 的下标，找不到返回 -1。 */
    private static int pathStart(String url) {
        int authority = url.indexOf("//");
        int from = authority < 0 ? "jdbc:mysql:".length() : authority + 2;
        return url.indexOf('/', from);
    }

    /**
     * 把原始异常翻译成「一句问题 + 一句怎么办」。
     */
    static String translate(SQLException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        int code = e.getErrorCode();
        if (code == ERR_BAD_DB || message.contains("Unknown database")) {
            return hint(
                    "数据库 iblog 不存在。",
                    "执行初始化脚本，或把 iblog.db.auto-init 设为 true 让后端自动建库。");
        }
        if (code == 1045 || message.contains("Access denied")) {
            return hint(
                    "数据库账号或密码不正确（Access denied）。",
                    "请核对 iblog-server/src/main/resources/application-dev.yml 里的 datasource 配置。");
        }
        if (message.contains("Communications link failure") || message.contains("Connection refused")) {
            return hint(
                    "连不上 MySQL 服务。",
                    "请确认 MySQL 已启动，地址/端口（默认 localhost:3306）配置正确。");
        }
        if (message.contains("CommunicationsException")
                || message.contains("Unable to connect")
                || message.contains("timed out")) {
            return hint(
                    "连接 MySQL 超时。",
                    "请检查 MySQL 是否启动、网络与端口是否可达。");
        }
        return hint("数据库检查未通过：" + message, "完整原因见下方 Caused by。");
    }

    /** 统一的提示块，保证所有数据库问题都长一个样。 */
    static String hint(String... lines) {
        StringBuilder builder = new StringBuilder();
        builder.append("\n==========================================================\n");
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                builder.append("  ").append(line).append('\n');
            }
        }
        builder.append("  环境准备详见 README「四、本地启动」\n");
        builder.append("==========================================================");
        return builder.toString();
    }
}
