package io.agentharness.store.jdbc;

import io.agentharness.store.StoreException;
import io.agentharness.store.datasource.DataSourceProvider;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 极小的 JDBC 辅助。不是 ORM，也不打算变成 ORM。
 *
 * <p>本项目的 SQL 一共不到十条，且每一条都在热路径上 ——
 * 引入 ORM 换来的是不可预测的 SQL 和一层难以排查的缓存。
 *
 * <p>jsonb 字段一律在 SQL 里写 {@code ?::jsonb}，参数按普通字符串传，
 * 这样辅助类不需要认识任何 PostgreSQL 特有类型。
 */
public final class Jdbc {

    private final DataSource dataSource;

    public Jdbc(DataSourceProvider provider) {
        this.dataSource = provider.dataSource();
    }

    public void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw translate("执行语句失败", sql, e);
        }
    }

    public int update(String sql, Object... params) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw translate("更新失败", sql, e);
        }
    }

    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> rows = queryList(sql, mapper, params);
        if (rows.size() > 1) {
            throw new StoreException("期望至多一行，实际 " + rows.size() + " 行：" + sql);
        }
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... params) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(mapper.map(resultSet));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException e) {
            throw translate("查询失败", sql, e);
        }
    }

    /**
     * 批量写入，单事务。
     *
     * <p>消息落库走这条：一批 delta 要么全部可见、要么全部不可见。
     * 部分成功会在消息表里留下序号空洞，而客户端的空窗判定依赖序号无洞（INV-10）。
     */
    public int[] batch(String sql, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return new int[0];
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (Object[] params : rows) {
                    bind(statement, params);
                    statement.addBatch();
                }
                int[] result = statement.executeBatch();
                connection.commit();
                return result;
            } catch (SQLException e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw translate("批量写入失败", sql, e);
        }
    }

    // ---------- 连接内操作：用于把多条语句放进同一个事务 ----------

    /**
     * 在给定连接上查询。
     *
     * <p>存在的理由只有一个：<b>让"分配序号"与"写入消息"能进同一个事务</b>。
     * 分开提交的话，写入失败时分配器已经推进了 —— 那些序号永远不会有对应的消息，
     * 消息序列里就多了一个永久空洞，而客户端的空窗判定要求序号无洞（INV-10）。
     */
    public <T> Optional<T> queryOne(Connection connection, String sql, RowMapper<T> mapper,
                                    Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.ofNullable(mapper.map(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw SqlErrors.translate("查询失败", sql, e);
        }
    }

    public int update(Connection connection, String sql, Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw SqlErrors.translate("更新失败", sql, e);
        }
    }

    public int[] batch(Connection connection, String sql, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return new int[0];
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Object[] params : rows) {
                bind(statement, params);
                statement.addBatch();
            }
            return statement.executeBatch();
        } catch (SQLException e) {
            throw SqlErrors.translate("批量写入失败", sql, e);
        }
    }

    public <T> T inTransaction(Function<Connection, T> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException e) {
                rollbackQuietly(connection);
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw translate("事务执行失败", "", e);
        }
    }

    private static void bind(PreparedStatement statement, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 回滚失败时连接大概率已经废了，交给池在归还时丢弃
        }
    }

    private static StoreException translate(String what, String sql, SQLException e) {
        return SqlErrors.translate(what, sql, e);
    }
}
