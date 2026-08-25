package io.agentharness.store.datasource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 一个够用的连接池。
 *
 * <p>刻意不引第三方池：这些模块最终要进现有的 servlet 项目，那边已经有自己的池子和监控。
 * 在这里绑死一个 HikariCP 只会在集成时多出一场依赖冲突和"两个池子谁说了算"的争论。
 * 需要换池时实现 {@link DataSourceProvider} 即可，这个类可以整体删掉。
 *
 * <p>不变量：<b>物理连接数 ≤ maxPoolSize</b>。
 * 由信号量保证 —— 只有拿到许可的线程才可能创建连接，而每个许可持有者最多持有一条连接。
 * 归还时先入空闲队列再释放许可，两步的顺序不能颠倒，否则会出现瞬时超额。
 */
public final class SimpleDataSource implements DataSource, AutoCloseable {

    /** 打开一条物理连接。抽出来是为了让池的行为可以脱离真实数据库测试。 */
    @FunctionalInterface
    interface ConnectionFactory {

        Connection open() throws SQLException;
    }

    private final DataSourceConfig config;
    private final ConnectionFactory factory;
    private final BlockingQueue<Pooled> idle;
    private final Semaphore permits;
    private volatile boolean closed;

    public SimpleDataSource(DataSourceConfig config) {
        this(config, null);
    }

    SimpleDataSource(DataSourceConfig config, ConnectionFactory factory) {
        this.config = config;
        this.factory = factory;
        this.idle = new ArrayBlockingQueue<>(config.maxPoolSize());
        this.permits = new Semaphore(config.maxPoolSize());
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (closed) {
            throw new SQLException("数据源已关闭");
        }

        boolean acquired;
        try {
            acquired = permits.tryAcquire(config.connectionTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("获取连接被中断", e);
        }
        if (!acquired) {
            throw new SQLTimeoutException("等待连接超时（" + config.connectionTimeout().toMillis()
                    + "ms，池上限 " + config.maxPoolSize() + "）—— 通常意味着有连接没有被关闭");
        }

        try {
            return wrap(takeUsable());
        } catch (SQLException | RuntimeException e) {
            permits.release();
            throw e;
        }
    }

    private Pooled takeUsable() throws SQLException {
        Pooled candidate;
        while ((candidate = idle.poll()) != null) {
            if (usable(candidate)) {
                return candidate;
            }
            closeQuietly(candidate.connection);
        }
        return new Pooled(open(), System.nanoTime());
    }

    private Connection open() throws SQLException {
        Connection connection = factory != null
                ? factory.open()
                : DriverManager.getConnection(config.jdbcUrl(), config.username(), config.password());
        connection.setAutoCommit(true);
        return connection;
    }

    private boolean usable(Pooled pooled) {
        long ageNanos = System.nanoTime() - pooled.createdAtNanos;
        if (ageNanos > config.maxLifetime().toNanos()) {
            return false;
        }
        try {
            return pooled.connection.isValid((int) config.validationTimeout().toSeconds());
        } catch (SQLException e) {
            return false;
        }
    }

    private Connection wrap(Pooled pooled) {
        return (Connection) Proxy.newProxyInstance(
                SimpleDataSource.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new Handle(pooled));
    }

    private void release(Pooled pooled) {
        // 顺序：先入队再放许可。颠倒会让别的线程在队列为空时拿到许可去新建连接，瞬时超额
        if (closed || !usable(pooled) || !idle.offer(pooled)) {
            closeQuietly(pooled.connection);
        }
        permits.release();
    }

    @Override
    public void close() {
        closed = true;
        Pooled pooled;
        while ((pooled = idle.poll()) != null) {
            closeQuietly(pooled.connection);
        }
    }

    /** 当前空闲连接数，用于自检与监控。 */
    public int idleCount() {
        return idle.size();
    }

    /**
     * 当前借出的连接数。
     *
     * <p>许可与"借出"一一对应：空闲连接是不持有许可的，
     * 所以这里不能再减一次 {@code idle.size()}，那会在连接归还后算出负数。
     */
    public int activeCount() {
        return config.maxPoolSize() - permits.availablePermits();
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 已经决定丢弃这条连接了，关闭失败没有额外可做的
        }
    }

    private record Pooled(Connection connection, long createdAtNanos) {
    }

    /** 把 {@code close()} 改写为归还，其余方法透传给真实连接。 */
    private final class Handle implements java.lang.reflect.InvocationHandler {

        private final Pooled pooled;
        private volatile boolean released;

        private Handle(Pooled pooled) {
            this.pooled = pooled;
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("close".equals(name)) {
                if (!released) {
                    released = true;
                    release(pooled);
                }
                return null;
            }
            if ("isClosed".equals(name)) {
                return released || pooled.connection.isClosed();
            }
            if ("toString".equals(name)) {
                return "PooledConnection[" + pooled.connection + "]";
            }
            if (released) {
                throw new SQLException("连接已归还池中，不能继续使用");
            }
            try {
                return method.invoke(pooled.connection, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    // ---------- DataSource 其余方法：本池不支持的一律明确拒绝，而不是静默忽略 ----------

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLException("不支持按调用方指定凭据，凭据在 DataSourceConfig 里配置");
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        // 日志走 SLF4J，不用 JDBC 的 PrintWriter
    }

    @Override
    public void setLoginTimeout(int seconds) {
        // 登录超时由 connectionTimeout 统一控制
    }

    @Override
    public int getLoginTimeout() {
        return (int) config.connectionTimeout().toSeconds();
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger(SimpleDataSource.class.getName());
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("无法解包为 " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
