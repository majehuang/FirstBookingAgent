package io.agentharness.engine.store;

import io.agentharness.store.StoreException;
import io.agentharness.store.jdbc.Jdbc;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@link AgentStateStore} 的 PostgreSQL 实现。
 *
 * <p>上游只提供 InMemory 与 JsonFile 两个实现，多 pod 下都不能用：
 * 前者进程一停就没了，后者按 pod 分叉。
 *
 * <p><b>两个必须知道的事实：</b>
 *
 * <p>其一，<b>这个接口是阻塞的</b>（{@code void save} / {@code Optional get}）。
 * HarnessAgent 在响应式链路内部调用它，因此每一轮推理都会有阻塞 JDBC 落在调用线程上。
 * 调用方必须把整条 agent 流 {@code subscribeOn(boundedElastic)}，
 * 否则会占死事件循环 —— 症状是全 pod 所有 session 一起卡（INV-7）。
 *
 * <p>其二，<b>这个接口表达不了 CAS</b>：{@code save} 不接收版本号，
 * 所以没有办法在接口语义内做"读到 v3 才允许写成 v4"。这里的写入是 LWW，
 * {@code version} 列只做递增计数与排查用。
 * 真正防双跑的是 lease（INV-3），不是状态存储。
 * 需要在存储层再加一道时用 {@link #saveIfVersion}，它不在上游接口里。
 */
public final class PostgresAgentStateStore implements AgentStateStore {

    private static final String UPSERT_SQL = """
            INSERT INTO agent_state (user_id, session_id, state_key, payload, is_list, version, updated_at)
            VALUES (?, ?, ?, ?::jsonb, ?, 0, now())
            ON CONFLICT (user_id, session_id, state_key) DO UPDATE
                SET payload = EXCLUDED.payload,
                    is_list = EXCLUDED.is_list,
                    version = agent_state.version + 1,
                    updated_at = now()
            """;

    private static final String CAS_SQL = """
            UPDATE agent_state
               SET payload = ?::jsonb, is_list = ?, version = version + 1, updated_at = now()
             WHERE user_id = ? AND session_id = ? AND state_key = ? AND version = ?
            """;

    private static final String SELECT_SQL = """
            SELECT payload, is_list FROM agent_state
             WHERE user_id = ? AND session_id = ? AND state_key = ?
            """;

    private static final String EXISTS_SQL = """
            SELECT 1 FROM agent_state WHERE user_id = ? AND session_id = ? LIMIT 1
            """;

    private static final String DELETE_SESSION_SQL =
            "DELETE FROM agent_state WHERE user_id = ? AND session_id = ?";

    private static final String DELETE_KEY_SQL =
            "DELETE FROM agent_state WHERE user_id = ? AND session_id = ? AND state_key = ?";

    private static final String LIST_SESSIONS_SQL =
            "SELECT DISTINCT session_id FROM agent_state WHERE user_id = ?";

    private final Jdbc jdbc;

    public PostgresAgentStateStore(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(String userId, String sessionId, String key, State state) {
        jdbc.update(UPSERT_SQL, userId, sessionId, key, codec().toJson(state), false);
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> states) {
        jdbc.update(UPSERT_SQL, userId, sessionId, key, codec().toJson(states), true);
    }

    /**
     * 带版本校验的写入。不在上游接口里，留给 P3 的双跑防护用。
     *
     * @return 是否写入成功；false 表示版本已被其它写入者推进
     */
    public boolean saveIfVersion(String userId, String sessionId, String key, State state, long expectedVersion) {
        return jdbc.update(CAS_SQL, codec().toJson(state), false,
                userId, sessionId, key, expectedVersion) == 1;
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        return jdbc.queryOne(SELECT_SQL, rs -> rs.getString("payload"), userId, sessionId, key)
                .filter(json -> json != null && !json.isBlank())
                .map(json -> deserialize(json, type, key));
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> type) {
        Optional<String> payload = jdbc.queryOne(SELECT_SQL, rs -> rs.getString("payload"),
                userId, sessionId, key);
        if (payload.isEmpty() || payload.get().isBlank()) {
            return List.of();
        }
        try {
            // 先按原始 List 读出来，再逐个 convertValue —— 泛型擦除下没法直接给出 List<T> 的 TypeReference
            List<?> raw = codec().fromJson(payload.get(), List.class);
            List<T> result = new ArrayList<>(raw.size());
            for (Object element : raw) {
                result.add(codec().convertValue(element, type));
            }
            return List.copyOf(result);
        } catch (RuntimeException e) {
            throw new StoreException("反序列化状态列表失败：" + key + " → " + type.getSimpleName(), e);
        }
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return jdbc.queryOne(EXISTS_SQL, rs -> rs.getInt(1), userId, sessionId).isPresent();
    }

    @Override
    public void delete(String userId, String sessionId) {
        jdbc.update(DELETE_SESSION_SQL, userId, sessionId);
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        jdbc.update(DELETE_KEY_SQL, userId, sessionId, key);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return Set.copyOf(new HashSet<>(
                jdbc.queryList(LIST_SESSIONS_SQL, rs -> rs.getString("session_id"), userId)));
    }

    private static <T extends State> T deserialize(String json, Class<T> type, String key) {
        try {
            return codec().fromJson(json, type);
        } catch (RuntimeException e) {
            throw new StoreException("反序列化状态失败：" + key + " → " + type.getSimpleName(), e);
        }
    }

    /**
     * 用 AgentScope 自己的编解码器，而不是我们的 ObjectMapper。
     *
     * <p>ContentBlock 是带 {@code @JsonTypeInfo} 的多态类型，
     * 换一个 mapper 就可能在反序列化时丢掉子类信息 —— 而这类问题只在读取旧会话时才暴露。
     */
    private static JsonCodec codec() {
        return JsonUtils.getJsonCodec();
    }
}
