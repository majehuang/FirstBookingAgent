package io.agentharness.store.schema;

import io.agentharness.store.StoreException;
import io.agentharness.store.datasource.DataSourceConfig;
import io.agentharness.store.datasource.SimpleDataSourceProvider;
import io.agentharness.store.jdbc.Jdbc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 建库的真库验证。默认跳过，需要 {@code AGENT_IT_JDBC_URL}。
 *
 * <p>值得单独验的原因：宿主机 glibc 与 PG 镜像内建库时的版本一旦不一致，
 * 从 {@code template1} 建库会被 collation 版本校验直接挡下来。
 * 我们改用 {@code template0} + C 排序规则绕开，这条路径只有真库能验证。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_IT_JDBC_URL", matches = ".+")
class DatabaseBootstrapIntegrationTest {

    private final String jdbcUrl = System.getenv("AGENT_IT_JDBC_URL");
    private final String username = envOrDefault("AGENT_IT_DB_USER", "agent");
    private final String password = envOrDefault("AGENT_IT_DB_PASSWORD", "");

    @Test
    void 已存在的库返回false_不重复建() {
        assertThat(new DatabaseBootstrap(jdbcUrl, username, password).ensureDatabase()).isFalse();
    }

    @Test
    @DisplayName("从 template0 建库 —— 绕开 glibc 版本漂移导致的 collation 校验失败")
    void 新库能建出来并可连接() {
        String fresh = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                .toLowerCase(Locale.ROOT);
        String freshUrl = PostgresUrl.parse(jdbcUrl).prefix() + "/" + fresh;

        assertThat(new DatabaseBootstrap(freshUrl, username, password).ensureDatabase()).isTrue();
        try {
            // 新库要真的能连上并建表，否则"建出来了"没有意义
            try (var provider = new SimpleDataSourceProvider(
                    DataSourceConfig.of(freshUrl, username, password).withMaxPoolSize(1))) {
                assertThat(new SchemaMigrator(new Jdbc(provider)).migrate()).isPositive();
            }
            // 再次调用应当识别为已存在
            assertThat(new DatabaseBootstrap(freshUrl, username, password).ensureDatabase()).isFalse();
        } finally {
            dropDatabase(fresh);
        }
    }

    @Test
    void 拒绝在维护库上建表() {
        String maintenanceUrl = PostgresUrl.parse(jdbcUrl).maintenanceUrl();

        assertThatThrownBy(() -> new DatabaseBootstrap(maintenanceUrl, username, password).ensureDatabase())
                .isInstanceOf(StoreException.class)
                .hasMessageContaining("维护库");
    }

    @Test
    void 非法库名在连库之前就被拒绝() {
        String injected = PostgresUrl.parse(jdbcUrl).prefix() + "/agent\";DROP DATABASE agent;--";

        assertThatThrownBy(() -> new DatabaseBootstrap(injected, username, password).ensureDatabase())
                .isInstanceOf(StoreException.class)
                .hasMessageContaining("非法字符");
    }

    private void dropDatabase(String database) {
        String maintenanceUrl = PostgresUrl.parse(jdbcUrl).maintenanceUrl();
        try (var provider = new SimpleDataSourceProvider(
                DataSourceConfig.of(maintenanceUrl, username, password).withMaxPoolSize(1))) {
            new Jdbc(provider).execute("DROP DATABASE IF EXISTS \"" + database + "\"");
        }
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
