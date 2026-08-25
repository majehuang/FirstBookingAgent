package io.agentharness.store.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;

/** 把当前行映射为对象。允许抛 SQLException，由 {@link Jdbc} 统一转成 StoreException。 */
@FunctionalInterface
public interface RowMapper<T> {

    T map(ResultSet resultSet) throws SQLException;
}
