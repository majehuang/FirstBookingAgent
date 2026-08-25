package io.agentharness.engine.store;

import io.agentharness.protocol.Json;
import io.agentharness.store.jdbc.Jdbc;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * {@link BaseStore} 的 PostgreSQL 实现 —— 开发规划 A 节的 workspace 方案 B。
 *
 * <p>HarnessAgent 默认开双层长期记忆，会往 workspace 写 {@code MEMORY.md} 与
 * {@code memory/YYYY-MM-DD.md}。多 pod 下用本地文件系统，记忆会按 pod 分叉：
 * 同一个用户在 A pod 记住的事，到 B pod 就不存在了。
 *
 * <p>与 {@link PostgresAgentStateStore} 不同，这个接口<b>自带 CAS</b>
 * （{@link #putIfVersion}），所以并发写同一份记忆时能真正做到后写者失败而不是覆盖。
 */
public final class PostgresBaseStore implements BaseStore {

    private static final String NAMESPACE_SEPARATOR = "/";

    private static final String SELECT_SQL = """
            SELECT item_key, value, version FROM agent_store_item
             WHERE namespace = ? AND item_key = ?
            """;

    private static final String UPSERT_SQL = """
            INSERT INTO agent_store_item (namespace, item_key, value, version, updated_at)
            VALUES (?, ?, ?::jsonb, 0, now())
            ON CONFLICT (namespace, item_key) DO UPDATE
                SET value = EXCLUDED.value,
                    version = agent_store_item.version + 1,
                    updated_at = now()
            """;

    private static final String CAS_SQL = """
            UPDATE agent_store_item
               SET value = ?::jsonb, version = version + 1, updated_at = now()
             WHERE namespace = ? AND item_key = ? AND version = ?
            """;

    private static final String CAS_INSERT_SQL = """
            INSERT INTO agent_store_item (namespace, item_key, value, version, updated_at)
            VALUES (?, ?, ?::jsonb, 0, now())
            ON CONFLICT (namespace, item_key) DO NOTHING
            """;

    private static final String SEARCH_SQL = """
            SELECT item_key, value, version FROM agent_store_item
             WHERE namespace = ?
             ORDER BY item_key
             OFFSET ? LIMIT ?
            """;

    private static final String DELETE_SQL =
            "DELETE FROM agent_store_item WHERE namespace = ? AND item_key = ?";

    private static final int MAX_LIMIT = 500;

    private final Jdbc jdbc;

    public PostgresBaseStore(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StoreItem get(List<String> namespace, String key) {
        return jdbc.queryOne(SELECT_SQL, PostgresBaseStore::mapItem, flatten(namespace), key)
                .orElse(null);
    }

    @Override
    public void put(List<String> namespace, String key, Map<String, Object> value) {
        jdbc.update(UPSERT_SQL, flatten(namespace), key, Json.write(value));
    }

    @Override
    public boolean putIfVersion(List<String> namespace, String key, Map<String, Object> value, long version) {
        String flat = flatten(namespace);
        // version 0 既可能是"还不存在"也可能是"存在且版本为 0"。
        // 先尝试条件插入：已存在时 DO NOTHING，落到下面的条件更新，两条路径都不会覆盖别人的写入
        if (version == 0) {
            int inserted = jdbc.update(CAS_INSERT_SQL, flat, key, Json.write(value));
            if (inserted == 1) {
                return true;
            }
        }
        return jdbc.update(CAS_SQL, Json.write(value), flat, key, version) == 1;
    }

    @Override
    public List<StoreItem> search(List<String> namespace, int offset, int limit) {
        int boundedLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        int boundedOffset = Math.max(offset, 0);
        return jdbc.queryList(SEARCH_SQL, PostgresBaseStore::mapItem,
                flatten(namespace), boundedOffset, boundedLimit);
    }

    @Override
    public void delete(List<String> namespace, String key) {
        jdbc.update(DELETE_SQL, flatten(namespace), key);
    }

    /** 命名空间是层级路径，拍平成一个字符串做主键的一半。 */
    static String flatten(List<String> namespace) {
        return namespace == null || namespace.isEmpty() ? "" : String.join(NAMESPACE_SEPARATOR, namespace);
    }

    @SuppressWarnings("unchecked")
    private static StoreItem mapItem(ResultSet rs) throws SQLException {
        String json = rs.getString("value");
        Map<String, Object> value = json == null || json.isBlank()
                ? Map.of()
                : Json.read(json, Map.class);
        return new StoreItem(rs.getString("item_key"), value, rs.getLong("version"));
    }
}
