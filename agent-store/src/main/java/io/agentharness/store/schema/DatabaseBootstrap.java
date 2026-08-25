package io.agentharness.store.schema;

import io.agentharness.store.StoreException;
import io.agentharness.store.datasource.DataSourceConfig;
import io.agentharness.store.datasource.SimpleDataSourceProvider;
import io.agentharness.store.jdbc.Jdbc;

/**
 * 建库。
 *
 * <p>连到维护库 {@code postgres} 上执行 {@code CREATE DATABASE}，
 * 因为目标库这时还不存在、连不上去。
 *
 * <p>{@code CREATE DATABASE} 不能在事务里执行，所以走的是自动提交的普通语句。
 */
public final class DatabaseBootstrap {

    private static final String EXISTS_SQL = "SELECT 1 FROM pg_database WHERE datname = ?";

    private final String jdbcUrl;
    private final String username;
    private final String password;

    /**
     * 建库语句。
     *
     * <p>两个选择都不是默认值，都有理由：
     * <ul>
     *   <li><b>{@code TEMPLATE template0}</b> —— 不从 template1 复制。
     *       宿主机上的 glibc 版本与 PG 镜像内建库时的版本不一致时，
     *       从 template1 建库会直接被 collation 版本校验挡下来。</li>
     *   <li><b>{@code LC_COLLATE 'C'}</b> —— 按字节序排。
     *       C 排序规则没有版本号，因此彻底绕开上面那类 glibc 版本漂移；
     *       代价是中文 ORDER BY 变成字节序而不是拼音序 ——
     *       本项目的排序只发生在 msg_seq（bigint）和文件路径上，不受影响。</li>
     * </ul>
     */
    static String createDatabaseSql(String database) {
        return "CREATE DATABASE \"" + database + "\""
                + " TEMPLATE template0 ENCODING 'UTF8' LC_COLLATE 'C' LC_CTYPE 'C'";
    }

    public DatabaseBootstrap(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    /**
     * 确保目标库存在。
     *
     * @return true 表示本次新建，false 表示原本就在
     */
    public boolean ensureDatabase() {
        PostgresUrl url = PostgresUrl.parse(jdbcUrl);
        if (url.isMaintenanceDatabase()) {
            throw new StoreException("拒绝在维护库 postgres 上建表，请把连接串指向业务库");
        }
        String database = url.assertSafeIdentifier();

        DataSourceConfig config = DataSourceConfig
                .of(url.maintenanceUrl(), username, password)
                .withMaxPoolSize(1);

        try (SimpleDataSourceProvider provider = new SimpleDataSourceProvider(config)) {
            Jdbc jdbc = new Jdbc(provider);
            if (jdbc.queryOne(EXISTS_SQL, rs -> rs.getInt(1), database).isPresent()) {
                return false;
            }
            // 库名已通过标识符校验，此处拼接是安全的；加引号以保留大小写
            jdbc.execute(createDatabaseSql(database));
            return true;
        }
    }
}
