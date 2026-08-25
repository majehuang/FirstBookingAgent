package io.agentharness.cli;

import io.agentharness.store.datasource.DataSourceConfig;
import io.agentharness.store.datasource.DataSourceProvider;
import io.agentharness.store.datasource.SimpleDataSourceProvider;
import picocli.CommandLine.Option;

/**
 * 数据库连接参数。多个子命令共用，用 picocli 的 mixin 混入。
 *
 * <p>三个参数都能从环境变量读，命令行给了则命令行优先：
 * {@code AGENT_JDBC_URL} / {@code AGENT_DB_USER} / {@code AGENT_DB_PASSWORD}。
 *
 * <p>密码<b>只能</b>走环境变量或交互输入，写在命令行上会进 shell 历史、
 * 也会出现在 {@code ps} 的输出里。另外两个走环境变量纯粹是为了少敲字 ——
 * 本地开发时几个进程要用同一套连接参数，一条 {@code export} 比每条命令都重复一遍强。
 */
public final class DbOptions {

    static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:5432/agent";
    static final String DEFAULT_USER = "agent";

    @Option(names = "--jdbc-url",
            description = "PostgreSQL 连接串，留空则读环境变量 AGENT_JDBC_URL，"
                    + "再没有就用 ${DEFAULT-VALUE}")
    String jdbcUrl;

    @Option(names = "--db-user",
            description = "数据库用户，留空则读环境变量 AGENT_DB_USER，再没有就用 ${DEFAULT-VALUE}")
    String username;

    @Option(names = "--db-password", description = "数据库密码，留空则读环境变量 AGENT_DB_PASSWORD")
    String password;

    @Option(names = "--db-pool-size", description = "连接池上限（默认 ${DEFAULT-VALUE}）")
    int poolSize = 10;

    /**
     * 建一个数据源。
     *
     * <p>集成进现有 servlet 项目时不要走这条路 —— 那边已经有自己的池子，
     * 直接 {@code DataSourceProvider.of(现有 DataSource)}，
     * 否则同一个进程里会跑出两个池，连接数上限也就失去意义了。
     */
    DataSourceProvider openProvider() {
        DataSourceConfig config = DataSourceConfig
                .of(resolveJdbcUrl(), resolveUser(), resolvePassword())
                .withMaxPoolSize(poolSize);
        return new SimpleDataSourceProvider(config);
    }

    String resolveJdbcUrl() {
        return firstPresent(jdbcUrl, System.getenv("AGENT_JDBC_URL"), DEFAULT_JDBC_URL);
    }

    String resolveUser() {
        return firstPresent(username, System.getenv("AGENT_DB_USER"), DEFAULT_USER);
    }

    /** 命令行参数优先，其次环境变量。两者都没有时返回空串，让 PG 自己报鉴权失败。 */
    String resolvePassword() {
        return firstPresent(password, System.getenv("AGENT_DB_PASSWORD"), "");
    }

    /** 命令行 → 环境变量 → 默认值。空串按"没给"处理。 */
    private static String firstPresent(String fromArgs, String fromEnv, String fallback) {
        if (fromArgs != null && !fromArgs.isBlank()) {
            return fromArgs;
        }
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return fallback;
    }
}
