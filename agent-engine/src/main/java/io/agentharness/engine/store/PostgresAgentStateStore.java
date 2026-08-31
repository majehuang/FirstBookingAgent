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
 * <p>其二，<b>它不是写者仲裁器，这是刻意的</b>（2026-08-28 冻结）。
 * {@code save} 不接收版本号，写入就是普通的 LWW ——
 * 而我们<b>不需要</b>它支持版本、CAS 或冲突重试。
 *
 * <p><b>同一 session 的 Worker 不双跑，唯一靠 lease（INV-3）。</b>
 * 牌子保证同时只有一个 Worker 在写；牌子丢了之后由 {@code LeaseFence} 立刻阻断写入。
 * 存储层不参与锁定、选主或 fencing。
 *
 * <p>{@code version} 列保留，但它<b>只用于计数、迁移与排障</b>，
 * <b>不能被当作并发控制</b>。曾经有过一个 {@code saveIfVersion}（真 CAS、但没有任何调用者），
 * 已随这次冻结删除 —— 一个摆在那里、名字又正好像"防双跑"的方法，
 * 迟早会有人拿它当第二道防线，然后开始怀疑 lease 是不是可以放松。
 * 那正是风险登记册 R-9 说的事。
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
