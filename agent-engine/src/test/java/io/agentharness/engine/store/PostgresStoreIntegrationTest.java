package io.agentharness.engine.store;

import io.agentharness.store.datasource.DataSourceConfig;
import io.agentharness.store.datasource.SimpleDataSourceProvider;
import io.agentharness.store.jdbc.Jdbc;
import io.agentharness.store.schema.SchemaMigrator;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两个 AgentScope Store 的真库验证。默认跳过，见
 * {@code PostgresMessageRepositoryIntegrationTest} 的说明。
 *
 * <p>重点是两件 mock 验证不了的事：
 * <ul>
 *   <li>{@code Msg} 这类带 {@code @JsonTypeInfo} 的多态类型能否原样往返 ——
 *       用错 ObjectMapper 时只有读旧会话才会暴露</li>
 *   <li>{@code putIfVersion} 是不是真的 CAS —— 并发下后写者必须失败而不是覆盖</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "AGENT_IT_JDBC_URL", matches = ".+")
class PostgresStoreIntegrationTest {

    private static SimpleDataSourceProvider provider;
    private static Jdbc jdbc;
    private static PostgresAgentStateStore stateStore;
    private static PostgresBaseStore baseStore;

    private final String userId = "it-user";
    private final String sessionId = "it-" + UUID.randomUUID();
    private final List<String> namespace = List.of("it", sessionId);

    @BeforeAll
    static void connect() {
        provider = new SimpleDataSourceProvider(DataSourceConfig.of(
                System.getenv("AGENT_IT_JDBC_URL"),
                envOrDefault("AGENT_IT_DB_USER", "agent"),
                envOrDefault("AGENT_IT_DB_PASSWORD", "")));
        jdbc = new Jdbc(provider);
        new SchemaMigrator(jdbc).migrate();
        stateStore = new PostgresAgentStateStore(jdbc);
        baseStore = new PostgresBaseStore(jdbc);
    }

    @AfterAll
    static void disconnect() {
        if (provider != null) {
            provider.close();
        }
    }

    @AfterEach
    void cleanup() {
        stateStore.delete(userId, sessionId);
        jdbc.update("DELETE FROM agent_store_item WHERE namespace = ?",
                PostgresBaseStore.flatten(namespace));
    }

    // ---------- AgentStateStore ----------

    @Test
    @DisplayName("多态内容块原样往返 —— 换错 mapper 只有读旧会话才会暴露")
    void 消息状态往返保真() {
        Msg original = Msg.builder()
                .id("m-1")
                .role(MsgRole.USER)
                .textContent("订一间明天北京的酒店")
                .build();

        stateStore.save(userId, sessionId, "last-msg", original);
        Optional<Msg> loaded = stateStore.get(userId, sessionId, "last-msg", Msg.class);

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getId()).isEqualTo("m-1");
        assertThat(loaded.get().getRole()).isEqualTo(MsgRole.USER);
        assertThat(loaded.get().getTextContent()).isEqualTo("订一间明天北京的酒店");
    }

    @Test
    void 列表状态往返保真且保持顺序() {
        List<Msg> history = List.of(
                Msg.builder().id("m-1").role(MsgRole.USER).textContent("第一句").build(),
                Msg.builder().id("m-2").role(MsgRole.ASSISTANT).textContent("第二句").build());

        stateStore.save(userId, sessionId, "history", history);
        List<Msg> loaded = stateStore.getList(userId, sessionId, "history", Msg.class);

        assertThat(loaded).extracting(Msg::getTextContent).containsExactly("第一句", "第二句");
        assertThat(loaded).extracting(Msg::getRole).containsExactly(MsgRole.USER, MsgRole.ASSISTANT);
    }

    @Test
    void 缺失的键返回空而不是抛异常() {
        assertThat(stateStore.get(userId, sessionId, "never-written", Msg.class)).isEmpty();
        assertThat(stateStore.getList(userId, sessionId, "never-written", Msg.class)).isEmpty();
        assertThat(stateStore.exists(userId, sessionId)).isFalse();
    }

    @Test
    void 会话列举与删除() {
        stateStore.save(userId, sessionId, "k",
                Msg.builder().id("m").role(MsgRole.USER).textContent("x").build());

        assertThat(stateStore.exists(userId, sessionId)).isTrue();
        assertThat(stateStore.listSessionIds(userId)).contains(sessionId);

        stateStore.delete(userId, sessionId);
        assertThat(stateStore.exists(userId, sessionId)).isFalse();
    }

    @Test
    @DisplayName("saveIfVersion 是真 CAS —— 版本不匹配时写入失败而不是覆盖")
    void 状态的条件写入() {
        Msg first = Msg.builder().id("m-1").role(MsgRole.USER).textContent("v0").build();
        stateStore.save(userId, sessionId, "cas-key", first);

        Msg second = Msg.builder().id("m-2").role(MsgRole.USER).textContent("v1").build();
        assertThat(stateStore.saveIfVersion(userId, sessionId, "cas-key", second, 0)).isTrue();

        // 版本已经被推进到 1，再拿 0 去写必须失败
        Msg stale = Msg.builder().id("m-3").role(MsgRole.USER).textContent("stale").build();
        assertThat(stateStore.saveIfVersion(userId, sessionId, "cas-key", stale, 0)).isFalse();

        assertThat(stateStore.get(userId, sessionId, "cas-key", Msg.class))
                .get().extracting(Msg::getTextContent).isEqualTo("v1");
    }

    @Test
    @DisplayName("状态按 (userId, sessionId) 隔离 —— 无状态引擎服务所有会话的前提")
    void 不同会话与不同用户的状态互不可见() {
        String otherSession = "it-" + UUID.randomUUID();
        String otherUser = "it-user-2";
        try {
            stateStore.save(userId, sessionId, "ctx", msg("甲会话"));
            stateStore.save(userId, otherSession, "ctx", msg("乙会话"));
            stateStore.save(otherUser, sessionId, "ctx", msg("另一个用户"));

            assertThat(text(stateStore.get(userId, sessionId, "ctx", Msg.class))).isEqualTo("甲会话");
            assertThat(text(stateStore.get(userId, otherSession, "ctx", Msg.class))).isEqualTo("乙会话");
            assertThat(text(stateStore.get(otherUser, sessionId, "ctx", Msg.class))).isEqualTo("另一个用户");

            // 删掉一个会话不能波及同一用户的其它会话
            stateStore.delete(userId, sessionId);
            assertThat(stateStore.get(userId, sessionId, "ctx", Msg.class)).isEmpty();
            assertThat(stateStore.get(userId, otherSession, "ctx", Msg.class)).isPresent();
            assertThat(stateStore.get(otherUser, sessionId, "ctx", Msg.class)).isPresent();
        } finally {
            stateStore.delete(userId, otherSession);
            stateStore.delete(otherUser, sessionId);
        }
    }

    @Test
    @DisplayName("workspace 记忆按用户隔离 —— IsolationScope.USER 的兑现点")
    void 记忆命名空间含用户维度() {
        List<String> otherUserNamespace = List.of("it", "other-user");
        try {
            baseStore.put(namespace, "MEMORY.md", Map.of("content", "甲的偏好"));
            baseStore.put(otherUserNamespace, "MEMORY.md", Map.of("content", "乙的偏好"));

            assertThat(baseStore.get(namespace, "MEMORY.md").value())
                    .containsEntry("content", "甲的偏好");
            assertThat(baseStore.get(otherUserNamespace, "MEMORY.md").value())
                    .containsEntry("content", "乙的偏好");
        } finally {
            baseStore.delete(otherUserNamespace, "MEMORY.md");
        }
    }

    private static Msg msg(String text) {
        return Msg.builder().id("m-" + text).role(MsgRole.USER).textContent(text).build();
    }

    private static String text(Optional<Msg> message) {
        return message.map(Msg::getTextContent).orElse(null);
    }

    // ---------- BaseStore（workspace 长期记忆） ----------

    @Test
    void 记忆条目的写入读取与检索() {
        baseStore.put(namespace, "MEMORY.md", Map.of("content", "用户偏好靠窗座位"));
        baseStore.put(namespace, "memory/2026-08-24.md", Map.of("content", "今天订了国贸"));

        StoreItem item = baseStore.get(namespace, "MEMORY.md");
        assertThat(item).isNotNull();
        assertThat(item.value()).containsEntry("content", "用户偏好靠窗座位");

        assertThat(baseStore.search(namespace, 0, 10))
                .extracting(StoreItem::key)
                .containsExactly("MEMORY.md", "memory/2026-08-24.md");
    }

    @Test
    void 不存在的条目返回null() {
        assertThat(baseStore.get(namespace, "缺失的条目")).isNull();
    }

    @Test
    @DisplayName("putIfVersion 防止并发写覆盖 —— 这是记忆不会被悄悄冲掉的依据")
    void 记忆条目的条件写入() {
        // version 0 且条目不存在 → 走条件插入
        assertThat(baseStore.putIfVersion(namespace, "MEMORY.md", Map.of("content", "第一版"), 0)).isTrue();
        assertThat(baseStore.get(namespace, "MEMORY.md").version()).isZero();

        // 拿正确版本写 → 成功，版本推进
        assertThat(baseStore.putIfVersion(namespace, "MEMORY.md", Map.of("content", "第二版"), 0)).isTrue();
        assertThat(baseStore.get(namespace, "MEMORY.md").version()).isEqualTo(1);

        // 拿过期版本写 → 失败，内容保持不变
        assertThat(baseStore.putIfVersion(namespace, "MEMORY.md", Map.of("content", "过期写入"), 0)).isFalse();
        assertThat(baseStore.get(namespace, "MEMORY.md").value()).containsEntry("content", "第二版");
    }

    @Test
    void 条目已存在时的条件插入不会覆盖() {
        baseStore.put(namespace, "MEMORY.md", Map.of("content", "已有内容"));

        // 已存在且版本为 0：条件插入被 DO NOTHING 吃掉，随后走条件更新
        assertThat(baseStore.putIfVersion(namespace, "MEMORY.md", Map.of("content", "新内容"), 0)).isTrue();
        assertThat(baseStore.get(namespace, "MEMORY.md").value()).containsEntry("content", "新内容");
    }

    @Test
    void 删除后检索不到() {
        baseStore.put(namespace, "MEMORY.md", Map.of("content", "x"));
        baseStore.delete(namespace, "MEMORY.md");

        assertThat(baseStore.get(namespace, "MEMORY.md")).isNull();
        assertThat(baseStore.search(namespace, 0, 10)).isEmpty();
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
