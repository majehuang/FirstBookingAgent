package io.agentharness.store.datasource;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimpleDataSourceTest {

    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();

    private DataSourceConfig config(int poolSize, Duration timeout) {
        return new DataSourceConfig("jdbc:fake://localhost/agent", "u", "p",
                poolSize, timeout, Duration.ofMinutes(30), Duration.ofSeconds(2));
    }

    private SimpleDataSource pool(int poolSize) {
        return pool(poolSize, Duration.ofMillis(200));
    }

    private SimpleDataSource pool(int poolSize, Duration timeout) {
        return new SimpleDataSource(config(poolSize, timeout), this::fakeConnection);
    }

    @Test
    void 归还后连接被复用_而不是每次新建() throws SQLException {
        try (SimpleDataSource dataSource = pool(2)) {
            for (int i = 0; i < 5; i++) {
                dataSource.getConnection().close();
            }
            assertThat(opened.get()).isEqualTo(1);
            assertThat(dataSource.idleCount()).isEqualTo(1);
        }
    }

    @Test
    void 物理连接数不超过池上限() throws SQLException {
        try (SimpleDataSource dataSource = pool(3)) {
            List<Connection> held = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                held.add(dataSource.getConnection());
            }

            assertThat(opened.get()).isEqualTo(3);
            assertThat(dataSource.activeCount()).isEqualTo(3);

            for (Connection connection : held) {
                connection.close();
            }
            assertThat(dataSource.activeCount()).isZero();
        }
    }

    @Test
    void 池满时等待超时_而不是无限期挂住() throws SQLException {
        try (SimpleDataSource dataSource = pool(1, Duration.ofMillis(100))) {
            Connection held = dataSource.getConnection();

            assertThatThrownBy(dataSource::getConnection)
                    .isInstanceOf(SQLTimeoutException.class)
                    .hasMessageContaining("没有被关闭");

            held.close();
            // 归还后立刻又能借到
            dataSource.getConnection().close();
        }
    }

    @Test
    void 归还后的句柄不能继续使用() throws SQLException {
        try (SimpleDataSource dataSource = pool(1)) {
            Connection connection = dataSource.getConnection();
            connection.close();

            assertThat(connection.isClosed()).isTrue();
            assertThatThrownBy(connection::createStatement)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("已归还池中");
        }
    }

    @Test
    void 重复close只归还一次_不会把许可放两遍() throws SQLException {
        try (SimpleDataSource dataSource = pool(1)) {
            Connection connection = dataSource.getConnection();
            connection.close();
            connection.close();

            assertThat(dataSource.idleCount()).isEqualTo(1);
            assertThat(dataSource.activeCount()).isZero();
        }
    }

    @Test
    void 关闭池时物理连接被真正关掉() throws SQLException {
        SimpleDataSource dataSource = pool(2);
        dataSource.getConnection().close();
        dataSource.getConnection().close();

        dataSource.close();

        assertThat(closed.get()).isEqualTo(1);
        assertThatThrownBy(dataSource::getConnection)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("已关闭");
    }

    @Test
    void 不支持按调用方指定凭据() {
        try (SimpleDataSource dataSource = pool(1)) {
            assertThatThrownBy(() -> dataSource.getConnection("other", "secret"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("DataSourceConfig");
        }
    }

    @Test
    void DataSource自身的解包与超时() throws SQLException {
        try (SimpleDataSource dataSource = pool(1, Duration.ofSeconds(7))) {
            assertThat(dataSource.isWrapperFor(SimpleDataSource.class)).isTrue();
            assertThat(dataSource.unwrap(SimpleDataSource.class)).isSameAs(dataSource);
            assertThat(dataSource.getLoginTimeout()).isEqualTo(7);
            assertThat(dataSource.getParentLogger()).isNotNull();

            assertThatThrownBy(() -> dataSource.unwrap(String.class))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("无法解包");
        }
    }

    @Test
    void 超过最大存活时长的连接被丢弃_而不是继续复用() throws SQLException {
        DataSourceConfig shortLived = new DataSourceConfig("jdbc:fake://localhost/agent", "u", "p",
                2, Duration.ofMillis(200), Duration.ofNanos(1), Duration.ofSeconds(2));
        try (SimpleDataSource dataSource = new SimpleDataSource(shortLived, this::fakeConnection)) {
            dataSource.getConnection().close();
            // 归还时已超龄，连接被关掉而不是入池
            assertThat(dataSource.idleCount()).isZero();
            assertThat(closed.get()).isEqualTo(1);
        }
    }

    /** 一条假连接：只回答池关心的那几个方法。 */
    private Connection fakeConnection() {
        opened.incrementAndGet();
        return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isValid" -> true;
                    case "isClosed" -> false;
                    case "close" -> {
                        closed.incrementAndGet();
                        yield null;
                    }
                    case "setAutoCommit", "commit", "rollback" -> null;
                    case "getAutoCommit" -> true;
                    case "toString" -> "FakeConnection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
