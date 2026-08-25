package io.agentharness.engine;

import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.SessionRef;
import io.agentharness.protocol.UserInstruction;
import io.agentharness.store.message.MessageRepository;
import io.agentharness.store.message.PendingMessage;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配层的行为验证 —— 不需要真实模型，也不需要数据库。
 *
 * <p>要证明的核心事实是：<b>一个无状态引擎实例服务所有 session</b>。
 * 这是 v2 agent 的模型，也是"不需要 Agent Factory / Session Cache 那整层抽象"的依据
 * （开发规划 H 节）。如果哪天有人为了"隔离"给每个 session 建一个引擎，
 * 这里会立刻红。
 */
class AgentScopeBackendTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final RecordingEngine engine = new RecordingEngine();
    private final InMemoryMessages repository = new InMemoryMessages();
    private final AgentScopeBackend backend = new AgentScopeBackend(engine, repository, "test");

    @AfterEach
    void tearDown() {
        backend.close();
    }

    @Test
    @DisplayName("多个 session 共用同一个引擎实例 —— 引擎是无状态的")
    void 引擎实例被所有session共用() {
        send("s-1", "第一个会话");
        send("s-2", "第二个会话");
        send("s-3", "第三个会话");

        assertThat(engine.instancesHandedOut()).isEqualTo(1);
        assertThat(engine.streamedSessions()).containsExactly("s-1", "s-2", "s-3");
    }

    @Test
    @DisplayName("会话之间的消息流互不串扰")
    void 每个session有独立的出站流() {
        send("s-1", "问题甲");
        send("s-2", "问题乙");

        List<String> first = drain("s-1");
        List<String> second = drain("s-2");

        assertThat(first).contains("问题甲").doesNotContain("问题乙");
        assertThat(second).contains("问题乙").doesNotContain("问题甲");
    }

    @Test
    @DisplayName("用户消息排在助手输出之前，且在同一个 seq 空间里")
    void 用户消息先于助手输出() {
        send("s-1", "帮我订酒店");

        List<ClientMessage> messages = repository.all("s-1");

        assertThat(messages.get(0).fromUser()).isTrue();
        assertThat(messages.get(0).msgSeq()).isEqualTo(1L);
        for (int i = 0; i < messages.size(); i++) {
            assertThat(messages.get(i).msgSeq()).isEqualTo(i + 1L);
        }
    }

    @Test
    @DisplayName("同一 instructionId 重投不会起第二轮，回执带回原来的 replyId")
    void 重复投递命中幂等() {
        SessionRef session = SessionRef.of("u", "s-1");
        UserInstruction instruction = UserInstruction.message("i-1", "帮我订酒店", Instant.now());

        var first = backend.send(session, instruction).block(TIMEOUT);
        var retry = backend.send(session, instruction).block(TIMEOUT);

        assertThat(retry.replyId()).isEqualTo(first.replyId());
        assertThat(retry.msgSeq()).isEqualTo(first.msgSeq());
        // 关键：模型只被调用了一次，同一句话不会被回答两遍
        assertThat(engine.streamedSessions()).containsExactly("s-1");
        assertThat(repository.all("s-1").stream().filter(ClientMessage::fromUser)).hasSize(1);
    }

    @Test
    void 历史拉取能力对外暴露() {
        send("s-1", "第一句");

        assertThat(backend.history()).isPresent();
        assertThat(backend.history().get().since(SessionRef.of("u", "s-1"), 0, 100))
                .isNotEmpty();
    }

    // ---------- 测试替身 ----------

    private void send(String sessionId, String text) {
        backend.send(SessionRef.of("u", sessionId),
                UserInstruction.message("i-" + sessionId, text, Instant.now())).block(TIMEOUT);
        // 引擎是同步的，但控制帧经由 Sinks 发布，给它一个调度周期
        backend.control(SessionRef.of("u", sessionId)).blockFirst(TIMEOUT);
    }

    private List<String> drain(String sessionId) {
        return backend.messages(SessionRef.of("u", sessionId))
                .take(Duration.ofMillis(60))
                .map(ClientMessage::fallbackText)
                .collectList()
                .block(TIMEOUT);
    }

    /** 记录被调用过多少次、服务过哪些 session 的假引擎。 */
    private static final class RecordingEngine implements TurnEngine {

        private final ConcurrentLinkedQueue<String> sessions = new ConcurrentLinkedQueue<>();
        private final AtomicInteger handedOut = new AtomicInteger();

        @Override
        public Flux<Event> stream(SessionRef session, String text) {
            sessions.add(session.sessionId());
            handedOut.compareAndSet(0, 1);
            Msg reply = Msg.builder()
                    .id("m-" + session.sessionId())
                    .role(MsgRole.ASSISTANT)
                    .content(TextBlock.builder().text("收到：" + text).build())
                    .build();
            return Flux.just(new Event(EventType.REASONING, reply, false));
        }

        @Override
        public void interrupt() {
        }

        @Override
        public void close() {
        }

        /** 这个假引擎自己就是单例，永远只有一个 —— 断言的是调用方没有去建第二个。 */
        int instancesHandedOut() {
            return handedOut.get();
        }

        List<String> streamedSessions() {
            return List.copyOf(sessions);
        }
    }

    /** 内存版消息表，语义与 PostgreSQL 实现对齐：事务性分配、instructionId 幂等。 */
    private static final class InMemoryMessages implements MessageRepository {

        private final Map<String, List<ClientMessage>> bySession = new HashMap<>();
        private final Map<String, ClientMessage> byInstruction = new HashMap<>();
        private final java.util.Set<String> claimed = new java.util.HashSet<>();

        @Override
        public synchronized long allocate(SessionRef session, int count) {
            return lastSeq(session) + 1;
        }

        @Override
        public synchronized List<ClientMessage> append(SessionRef session, List<PendingMessage> pending) {
            List<ClientMessage> stored = bySession.computeIfAbsent(session.sessionId(),
                    key -> new ArrayList<>());
            List<ClientMessage> written = new ArrayList<>(pending.size());
            long seq = lastSeq(session);
            for (PendingMessage draft : pending) {
                seq++;
                ClientMessage message = new ClientMessage(seq, draft.replyId(), draft.blockId(),
                        draft.role(), draft.type(), draft.fallbackText(), draft.payload(),
                        draft.createdAt());
                stored.add(message);
                written.add(message);
            }
            return List.copyOf(written);
        }

        @Override
        public synchronized UserMessageOutcome appendUserMessage(SessionRef session, String replyId,
                                                                 String blockId, String text,
                                                                 String instructionId) {
            String key = session.sessionId() + "/" + instructionId;
            ClientMessage existing = byInstruction.get(key);
            if (existing != null) {
                return new UserMessageOutcome(existing, false);
            }
            long seq = lastSeq(session) + 1;
            ClientMessage message = ClientMessage.userText(seq, replyId, blockId, text, Instant.now());
            bySession.computeIfAbsent(session.sessionId(), k -> new ArrayList<>()).add(message);
            byInstruction.put(key, message);
            return new UserMessageOutcome(message, true);
        }

        @Override
        public synchronized List<ClientMessage> since(SessionRef session, long sinceSeq, int limit) {
            return all(session.sessionId()).stream()
                    .filter(m -> m.msgSeq() > sinceSeq)
                    .limit(limit)
                    .toList();
        }

        @Override
        public synchronized java.util.Optional<ClientMessage> findByInstruction(
                SessionRef session, String instructionId) {
            return java.util.Optional.ofNullable(
                    byInstruction.get(session.sessionId() + "/" + instructionId));
        }

        @Override
        public synchronized boolean claimTurn(SessionRef session, String instructionId) {
            return claimed.add(session.sessionId() + "/" + instructionId);
        }

        @Override
        public synchronized long lastSeq(SessionRef session) {
            List<ClientMessage> stored = bySession.get(session.sessionId());
            return stored == null || stored.isEmpty() ? 0 : stored.get(stored.size() - 1).msgSeq();
        }

        @Override
        public synchronized int markSuperseded(SessionRef session, String replyId) {
            return 0;
        }

        synchronized List<ClientMessage> all(String sessionId) {
            return List.copyOf(bySession.getOrDefault(sessionId, List.of()));
        }
    }
}
