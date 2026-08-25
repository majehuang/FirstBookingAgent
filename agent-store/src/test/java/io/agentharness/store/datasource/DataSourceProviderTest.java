package io.agentharness.store.datasource;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class DataSourceProviderTest {

    @Test
    void 外部注入的数据源不会被关掉() throws Exception {
        // 这是集成进 servlet 项目时最关键的一条：
        // 我们的模块关闭时若把宿主的池一起关了，整个应用的数据库访问都会挂
        AtomicBoolean hostPoolClosed = new AtomicBoolean(false);
        DataSource hostDataSource = fakeDataSource(hostPoolClosed);

        try (var provider = DataSourceProvider.of(hostDataSource)) {
            assertThat(provider.dataSource()).isSameAs(hostDataSource);
        }

        assertThat(hostPoolClosed).isFalse();
    }

    @Test
    void 自建的数据源在关闭时被真正释放() {
        SimpleDataSourceProvider provider = new SimpleDataSourceProvider(
                DataSourceConfig.of("jdbc:postgresql://localhost:5432/agent", "u", "p"));

        assertThat(provider.dataSource()).isSameAs(provider.pool());
        provider.close();

        assertThat(provider.pool().idleCount()).isZero();
    }

    private static DataSource fakeDataSource(AtomicBoolean closedFlag) {
        return (DataSource) Proxy.newProxyInstance(
                DataSourceProviderTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        closedFlag.set(true);
                    }
                    return null;
                });
    }
}
