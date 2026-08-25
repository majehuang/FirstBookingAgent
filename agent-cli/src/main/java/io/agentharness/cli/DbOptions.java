package io.agentharness.cli;

import io.agentharness.store.datasource.DataSourceConfig;
import io.agentharness.store.datasource.DataSourceProvider;
import io.agentharness.store.datasource.SimpleDataSourceProvider;
import picocli.CommandLine.Option;

/**
 * 数据库连接参数。多个子命令共用，用 picocli 的 mixin 混入。
 *
 * <p>密码支持从环境变量 {@code AGENT_DB_PASSWORD} 读 —— 写在命令行上会进 shell 历史，
 * 也会出现在 {@code ps} 的输出里。
 */
public final class DbOptions {

    @Option(names = "--jdbc-url", description = "PostgreSQL 连接串（默认 ${DEFAULT-VALUE}）")
    String jdbcUrl = "jdbc:postgresql://localhost:5432/agent";

    @Option(names = "--db-user", description = "数据库用户（默认 ${DEFAULT-VALUE}）")
    String username = "agent";

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
                .of(jdbcUrl, username, resolvePassword())
                .withMaxPoolSize(poolSize);
        return new SimpleDataSourceProvider(config);
    }

    /** 命令行参数优先，其次环境变量。两者都没有时返回空串，让 PG 自己报鉴权失败。 */
    String resolvePassword() {
        if (password != null && !password.isBlank()) {
            return password;
        }
        String fromEnv = System.getenv("AGENT_DB_PASSWORD");
        return fromEnv == null ? "" : fromEnv;
    }
}
