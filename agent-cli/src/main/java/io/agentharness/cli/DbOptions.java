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
    /**
     * 建数据源，并<b>当场验一次连通</b>，连不上直接失败。
     *
     * <p>不验的话客户端会以半死不活的状态起来：界面正常、能打字，
     * 然后 history、ack、对账各报一次错，中间还夹着能正常工作的回复
     * （回复走 Redis 不经 PG）。那种现场比直接失败难查得多。
     *
     * <p>{@code doctor} 与 {@code migrate} 不走这条 —— 它们的职责就是报告连不上，
     * 在这里抛异常会把它们的输出变成一句堆栈。
     */
    DataSourceProvider openVerifiedProvider() {
        DataSourceProvider provider = openProvider();
        try (java.sql.Connection probe = provider.dataSource().getConnection()) {
            probe.isValid(PROBE_TIMEOUT_SECONDS);
            return provider;
        } catch (Exception e) {
            try {
                provider.close();
            } catch (Exception ignored) {
                // 连都没连上，关闭失败没有额外信息
            }
            throw new IllegalStateException(diagnostic(rootMessage(e)), e);
        }
    }

    private static final int PROBE_TIMEOUT_SECONDS = 5;

    /**
     * 把三个参数各自的<b>来源</b>摆出来。
     *
     * <p>这才是有用的部分：光说"鉴权失败"看不出问题在哪，
     * 而"用户 agent（默认值）"一眼就能看出环境变量没送到 ——
     * IDE 的运行配置不继承 shell 的 export，这个坑踩过的人都知道，没踩过的查半天。
     */
    String diagnostic(String reason) {
        return "数据库连不上：" + reason + "\n"
                + "\n  连接串  " + describe(resolveJdbcUrl(), jdbcUrl, System.getenv("AGENT_JDBC_URL"))
                + "\n  用户    " + describe(resolveUser(), username, System.getenv("AGENT_DB_USER"))
                + "\n  密码    " + (resolvePassword().isEmpty() ? "未设置"
                        : describe("已设置", password == null ? null : "已设置",
                                System.getenv("AGENT_DB_PASSWORD") == null ? null : "已设置"))
                + "\n"
                + "\n  用户名与密码从 AGENT_DB_USER / AGENT_DB_PASSWORD 读，也可用 --db-user 覆盖。"
                + "\n  在 IDE 里运行要在「运行配置 → 环境变量」里设 —— 它不继承 shell 的 export。";
    }

    private static String describe(String effective, String fromArgs, String fromEnv) {
        String origin = fromArgs != null && !fromArgs.isBlank() ? "命令行"
                : fromEnv != null && !fromEnv.isBlank() ? "环境变量"
                : "默认值";
        return effective + "（" + origin + "）";
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

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
