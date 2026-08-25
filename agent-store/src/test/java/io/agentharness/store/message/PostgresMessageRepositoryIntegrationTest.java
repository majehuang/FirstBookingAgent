package io.agentharness.store.message;

import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.MessageRole;
import io.agentharness.protocol.MessageType;
import io.agentharness.protocol.SessionRef;
import io.agentharness.store.datasource.DataSourceConfig;
import io.agentharness.store.datasource.SimpleDataSourceProvider;
import io.agentharness.store.jdbc.Jdbc;
import io.agentharness.store.schema.SchemaMigrator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.agentharness.protocol.Json;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真库集成测试。
 *
 * <p>默认<b>跳过</b>，设置环境变量后才跑：
 * <pre>
 * export AGENT_IT_JDBC_URL=jdbc:postgresql://localhost:5432/agent
 * export AGENT_IT_DB_USER=admin
 * export AGENT_IT_DB_PASSWORD=...
 * mvn test
 * </pre>
 *
 * <p>为什么必须有真库：这些 SQL 的价值全在 PostgreSQL 的语义上 ——
 * {@code ON CONFLICT ... RETURNING} 的原子性、jsonb 的往返保真、部分索引的过滤，
 * 没有一样是 mock 能验证的。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_IT_JDBC_URL", matches = ".+")
class PostgresMessageRepositoryIntegrationTest {

    private static SimpleDataSourceProvider provider;
    private static Jdbc jdbc;
    private static PostgresMessageRepository repository;

    private final SessionRef session = SessionRef.of("it-user", "it-" + UUID.randomUUID());

    @BeforeAll
    static void connect() {
        provider = new SimpleDataSourceProvider(DataSourceConfig.of(
                System.getenv("AGENT_IT_JDBC_URL"),
                envOrDefault("AGENT_IT_DB_USER", "agent"),
                envOrDefault("AGENT_IT_DB_PASSWORD", "")));
        jdbc = new Jdbc(provider);
        new SchemaMigrator(jdbc).migrate();
        repository = new PostgresMessageRepository(jdbc);
    }

    @AfterAll
    static void disconnect() {
        if (provider != null) {
            provider.close();
        }
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM agent_message WHERE session_id = ?", session.sessionId());
        jdbc.update("DELETE FROM agent_msg_seq WHERE session_id = ?", session.sessionId());
    }

    @Test
    @DisplayName("连续分配无重叠无空洞 —— INV-10 的一半靠这个语句保证")
    void 序号分配单调且连续() {
        assertThat(repository.allocate(session, 3)).isEqualTo(1L);
        assertThat(repository.allocate(session, 2)).isEqualTo(4L);
        assertThat(repository.allocate(session, 1)).isEqualTo(6L);
    }

    @Test
    void 并发分配不会拿到重叠的段() throws Exception {
        int threads = 8;
        int perThread = 10;
        var results = new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        var latch = new java.util.concurrent.CountDownLatch(threads);

        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        for (int j = 0; j < perThread; j++) {
                            results.add(repository.allocate(session, 1));
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertThat(latch.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }

        List<Long> sorted = results.stream().sorted().toList();
        assertThat(sorted).hasSize(threads * perThread);
        // 无重复、无空洞：排序后必须是 1..N 的连续整数
        for (int i = 0; i < sorted.size(); i++) {
            assertThat(sorted.get(i)).isEqualTo(i + 1L);
        }
    }

    @Test
    void 消息往返保真_含jsonb载荷与时间戳() {
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        List<ClientMessage> written = repository.append(session, List.of(
                assistant("r-1", "b-1", MessageType.TEXT_DELTA, "你好", Map.of(), at),
                assistant("r-1", "b-2", MessageType.CARD, "3 家酒店",
                        Map.of("title", "为你找到 3 家酒店", "count", 3), at)));

        List<ClientMessage> loaded = repository.since(session, 0, 100);

        assertThat(loaded).hasSize(2);
        assertThat(loaded).isEqualTo(written);
        assertThat(loaded.get(0).fallbackText()).isEqualTo("你好");
        assertThat(loaded.get(1).payload())
                .containsEntry("title", "为你找到 3 家酒店")
                .containsEntry("count", 3);
        assertThat(repository.lastSeq(session)).isEqualTo(written.get(1).msgSeq());
    }

    @Test
    void 历史拉取严格大于游标() {
        Instant at = Instant.now();
        List<ClientMessage> written = repository.append(session, List.of(
                assistant("r-1", "b-1", MessageType.TEXT_DELTA, "一", Map.of(), at),
                assistant("r-1", "b-1", MessageType.TEXT_DELTA, "二", Map.of(), at),
                assistant("r-1", "b-1", MessageType.TEXT_DELTA, "三", Map.of(), at)));
        long first = written.get(0).msgSeq();

        assertThat(repository.since(session, first, 100))
                .extracting(ClientMessage::fallbackText).containsExactly("二", "三");
        assertThat(repository.since(session, first + 2, 100)).isEmpty();
    }

    @Test
    @DisplayName("写入失败不烧序号 —— 分配与写入在同一个事务里（INV-10）")
    void 写入失败后序号水位不推进() {
        repository.append(session, List.of(
                assistant("r-1", "b-1", MessageType.TEXT_DELTA, "第一条", Map.of(), Instant.now())));
        long waterMarkBefore = repository.lastSeq(session);

        // 注入一条 Jackson 序列化不了的载荷：真实场景里是工具返回了不可序列化的对象
        assertThatThrownBy(() -> repository.append(session, List.of(
                assistant("r-1", "b-2", MessageType.TEXT_DELTA, "会失败的一条",
                        Map.of("bad", new Object()), Instant.now()))))
                .isInstanceOf(RuntimeException.class);

        // 失败之后紧接着写一条：它必须拿到紧邻的下一个序号，中间不能有洞
        List<ClientMessage> next = repository.append(session, List.of(
                assistant("r-1", "b-3", MessageType.TEXT_DELTA, "第二条", Map.of(), Instant.now())));

        assertThat(next.get(0).msgSeq()).isEqualTo(waterMarkBefore + 1);
        assertThat(repository.since(session, 0, 100))
                .extracting(ClientMessage::fallbackText).containsExactly("第一条", "第二条");
    }

    @Test
    @DisplayName("整批成功或整批不可见 —— 不存在半批落库")
    void 批量写入没有中间态() {
        long before = repository.lastSeq(session);

        assertThatThrownBy(() -> repository.append(session, List.of(
                assistant("r-1", "b-1", MessageType.TEXT_DELTA, "好的", Map.of(), Instant.now()),
                assistant("r-1", "b-2", MessageType.CARD, "卡片",
                        Map.of("bad", new Object()), Instant.now()))))
                .isInstanceOf(RuntimeException.class);

        assertThat(repository.since(session, 0, 100)).isEmpty();
        assertThat(repository.lastSeq(session)).isEqualTo(before);
    }

    @Test
    @DisplayName("同一 instructionId 并发重试只落一条，且后续序号连续")
    void 并发重试不产生重复也不产生空洞() throws Exception {
        int threads = 8;
        var outcomes = new java.util.concurrent.ConcurrentLinkedQueue<
                MessageRepository.UserMessageOutcome>();
        var latch = new java.util.concurrent.CountDownLatch(threads);

        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                int index = i;
                pool.submit(() -> {
                    try {
                        outcomes.add(repository.appendUserMessage(
                                session, "r-" + index, "u-i1", "帮我订酒店", "i1"));
                    } catch (RuntimeException ignored) {
                        // 并发下唯一索引可能让部分线程失败，那是预期的
                    } finally {
                        latch.countDown();
                    }
                });
            }
            assertThat(latch.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }

        // 无论多少线程同时重试，消息表里只能有一条
        assertThat(repository.since(session, 0, 100)).hasSize(1);
        assertThat(outcomes.stream().filter(
                MessageRepository.UserMessageOutcome::inserted).count()).isEqualTo(1);

        // 而且后续消息的序号紧接其后，没有被并发重试烧掉
        long userSeq = repository.since(session, 0, 100).get(0).msgSeq();
        List<ClientMessage> next = repository.append(session, List.of(
                assistant("r-1", "b-1", MessageType.TEXT_DELTA, "好的", Map.of(), Instant.now())));
        assertThat(next.get(0).msgSeq()).isEqualTo(userSeq + 1);
    }

    @Test
    @DisplayName("多 session 交错写入，每个 session 各自从 1 开始连续")
    void 多session的序号互不干扰() {
        SessionRef other = SessionRef.of("it-user", "it-" + UUID.randomUUID());
        try {
            for (int round = 0; round < 5; round++) {
                repository.append(session, List.of(assistant("r-1", "b-1",
                        MessageType.TEXT_DELTA, "A" + round, Map.of(), Instant.now())));
                repository.append(other, List.of(assistant("r-1", "b-1",
                        MessageType.TEXT_DELTA, "B" + round, Map.of(), Instant.now())));
            }

            assertSequenceIsOneToN(session, 5);
            assertSequenceIsOneToN(other, 5);
        } finally {
            jdbc.update("DELETE FROM agent_message WHERE session_id = ?", other.sessionId());
            jdbc.update("DELETE FROM agent_msg_seq WHERE session_id = ?", other.sessionId());
        }
    }

    private void assertSequenceIsOneToN(SessionRef target, int expectedCount) {
        List<ClientMessage> messages = repository.since(target, 0, 100);
        assertThat(messages).hasSize(expectedCount);
        for (int i = 0; i < messages.size(); i++) {
            assertThat(messages.get(i).msgSeq())
                    .as("%s 的第 %d 条", target.sessionId(), i + 1)
                    .isEqualTo(i + 1L);
        }
    }

    @Test
    void 被重跑作废的reply不再出现在历史里() {
        Instant at = Instant.now();
        repository.append(session, List.of(
                assistant("r-old", "b-1", MessageType.TEXT_DELTA, "旧的回复", Map.of(), at),
                assistant("r-new", "b-2", MessageType.TEXT_DELTA, "新的回复", Map.of(), at)));

        assertThat(repository.markSuperseded(session, "r-old")).isEqualTo(1);

        assertThat(repository.since(session, 0, 100))
                .extracting(ClientMessage::fallbackText).containsExactly("新的回复");
    }

    @Test
    @DisplayName("重跑作废助手输出，但用户说过的话不会跟着消失")
    void 作废不影响用户消息() {
        repository.appendUserMessage(session, "r-old", "u-i1", "帮我订酒店", "i1");
        repository.append(session, List.of(
                assistant("r-old", "b-1", MessageType.TEXT_DELTA, "旧的回复", Map.of(), Instant.now())));

        repository.markSuperseded(session, "r-old");

        assertThat(repository.since(session, 0, 100))
                .extracting(ClientMessage::fallbackText).containsExactly("帮我订酒店");
    }

    @Test
    @DisplayName("同一 instructionId 重投只会有一条用户消息，且不烧序号")
    void 用户消息按instructionId幂等() {
        var first = repository.appendUserMessage(session, "r-1", "u-i1", "订一间酒店", "i1");
        assertThat(first.inserted()).isTrue();
        long seqAfterFirst = repository.lastSeq(session);

        // 客户端超时后带同一个 instructionId 重试
        var retry = repository.appendUserMessage(session, "r-2", "u-i1", "订一间酒店", "i1");

        assertThat(retry.inserted()).isFalse();
        assertThat(retry.message().msgSeq()).isEqualTo(first.message().msgSeq());
        // 关键：重试没有分配新序号，序列里不会因此出现空洞（INV-10）
        assertThat(repository.lastSeq(session)).isEqualTo(seqAfterFirst);
        assertThat(repository.since(session, 0, 100)).hasSize(1);
        // 回执要带回原来那个 replyId，否则客户端会去等一个不存在的 turn
        assertThat(retry.message().replyId()).isEqualTo("r-1");
    }

    @Test
    void 不同instructionId各自成一条() {
        repository.appendUserMessage(session, "r-1", "u-i1", "第一句", "i1");
        repository.appendUserMessage(session, "r-2", "u-i2", "第二句", "i2");

        assertThat(repository.since(session, 0, 100))
                .extracting(ClientMessage::fallbackText).containsExactly("第一句", "第二句");
    }

    @Test
    void 用户消息的role与类型正确落库() {
        var outcome = repository.appendUserMessage(session, "r-1", "u-i1", "订一间酒店", "i1");

        ClientMessage loaded = repository.since(session, 0, 10).get(0);
        assertThat(loaded.fromUser()).isTrue();
        assertThat(loaded.role()).isEqualTo(MessageRole.USER);
        assertThat(loaded.type()).isEqualTo(MessageType.TEXT);
        assertThat(loaded).isEqualTo(outcome.message());
    }

    @Test
    @DisplayName("FRZ-011 卡片经 jsonb 往返后逐字节一致 —— 重开会话所见即当时所见")
    void 卡片经数据库往返后线格式不变() {
        // 故意用不按字典序的插入顺序，且嵌套两层
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("rating", "4.8★");
        item.put("name", "北京国贸大酒店");
        item.put("price", "¥1,280");

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("title", "为你找到 1 家酒店");
        card.put("items", List.of(item));
        card.put("dataAsOf", "2026-08-25 10:00");

        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ClientMessage written = repository.append(session, List.of(new PendingMessage(
                "r-1", "b-1", MessageRole.ASSISTANT, MessageType.CARD,
                "为你找到 1 家酒店：北京国贸大酒店 ¥1,280 4.8★", card, at))).get(0);

        // 首次出站的线格式
        String firstWire = Json.write(written);

        // 清空客户端状态，从消息表重新加载
        ClientMessage reloaded = repository.since(session, 0, 10).get(0);
        String rebuiltWire = Json.write(reloaded);

        // PostgreSQL 的 jsonb 会按自己的规则重排对象键。
        // 唯一能让两边对上的办法是序列化时统一按键排序 —— 插入序活不过 jsonb
        assertThat(rebuiltWire).isEqualTo(firstWire);
        assertThat(reloaded).isEqualTo(written);
        assertThat(reloaded.fallbackText())
                .isEqualTo("为你找到 1 家酒店：北京国贸大酒店 ¥1,280 4.8★");
    }

    @Test
    void 载荷从数据库读回后仍然是深冻结的() {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("items", List.of(Map.of("name", "甲")));

        repository.append(session, List.of(new PendingMessage("r-1", "b-1",
                MessageRole.ASSISTANT, MessageType.CARD, "甲", card, Instant.now())));

        ClientMessage reloaded = repository.since(session, 0, 10).get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) reloaded.payloadValue("items");

        assertThatThrownBy(() -> items.get(0).put("name", "乙"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 空session的水位为0_而不是报错() {
        assertThat(repository.lastSeq(session)).isZero();
        assertThat(repository.since(session, 0, 10)).isEmpty();
    }

    private static PendingMessage assistant(String replyId, String blockId, MessageType type,
                                            String text, Map<String, Object> payload, Instant at) {
        return new PendingMessage(replyId, blockId, MessageRole.ASSISTANT, type, text, payload, at);
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
