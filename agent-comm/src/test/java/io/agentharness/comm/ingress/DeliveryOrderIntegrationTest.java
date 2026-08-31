package io.agentharness.comm.ingress;

import io.agentharness.keys.KeyNamespace;
import io.agentharness.protocol.Ack;
import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.SessionRef;
import io.agentharness.protocol.UserInstruction;
import io.agentharness.redis.RedisConfig;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.store.message.MessageRepository;
import io.agentharness.store.message.PendingMessage;
import io.lettuce.core.Range;
import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖 Test/P3 的 <b>DLV-002、DLV-003、DLV-004</b>：投递的故障终态。
 *
 * <p>默认跳过，给定 Redis 连接串才跑：
 * <pre>export AGENT_IT_REDIS_URI=redis://localhost:6379</pre>
 *
 * <h2>为什么单独连一个测试库</h2>
 * 这几条要<b>把写入打失败</b>。让 {@code XADD inbox} 失败只需污染本 session 自己的 key，
 * 但让 {@code XADD ready} 失败就得污染<b>全局共享</b>的 ready ——
 * 在默认库上做这件事会波及同一台 Redis 上的其它一切。
 * 所以整个类连到 {@code /15}，脏活都关在里面，跑完只删自己建的 key。
 *
 * <h2>失败注入的手法</h2>
 * 不用代理也不用 mock：把目标 key 先 {@code SET} 成字符串，
 * 之后对它的 {@code XADD} 就会返回 {@code WRONGTYPE}。
 * 这是真实的 Redis 错误路径，而不是我们自己编的异常。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_IT_REDIS_URI", matches = ".+")
class DeliveryOrderIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** 专用测试库：这些用例会故意污染 ready。 */
    private static final int TEST_DATABASE = 15;

    private static RedisRuntime runtime;

    private final SessionRef session = SessionRef.of("it-user", "it-" + UUID.randomUUID());
    private final RecordingRepository repository = new RecordingRepository();

    @BeforeAll
    static void connect() {
        RedisURI uri = RedisURI.create(System.getenv("AGENT_IT_REDIS_URI"));
        uri.setDatabase(TEST_DATABASE);
        runtime = RedisRuntime.open(RedisConfig.of(uri.toString()));
    }

    @AfterAll
    static void disconnect() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @AfterEach
    void cleanUp() {
        runtime.commands().del(KeyNamespace.inbox(session.sessionId()), KeyNamespace.READY)
                .block(TIMEOUT);
    }

    private RedisInstructionPublisher publisher() {
        return new RedisInstructionPublisher(runtime, repository);
    }

    private UserInstruction instruction() {
        return UserInstruction.message("i-" + UUID.randomUUID(), "帮我订酒店", Instant.now());
    }

    /** 把 key 变成字符串，之后对它的 XADD 必然 WRONGTYPE。 */
    private void breakStream(String key) {
        runtime.commands().set(key, "不是一个 stream").block(TIMEOUT);
    }

    private long inboxLength() {
        return runtime.commands().xlen(KeyNamespace.inbox(session.sessionId()))
                .onErrorReturn(0L).block(TIMEOUT);
    }

    private long readyLength() {
        return runtime.commands().xlen(KeyNamespace.READY).onErrorReturn(0L).block(TIMEOUT);
    }

    @Test
    @DisplayName("DLV-002 inbox 写失败时不写 ready，也不落库")
    void inbox失败则不继续() {
        breakStream(KeyNamespace.inbox(session.sessionId()));

        assertThatThrownBy(() -> publisher().publish(session, instruction()).block(TIMEOUT))
                .hasMessageContaining("写 inbox 失败");

        assertThat(readyLength())
                .as("ready 一条都没写 —— 顺序反了的话这里会是 1")
                .isZero();
        assertThat(repository.calls())
                .as("也没落库：先落库的话会留下一条永远等不到回复的 USER 消息")
                .isZero();
    }

    @Test
    @DisplayName("DLV-003 ready 写失败时保留 inbox 条目，不回滚，不落库")
    void ready失败则保留inbox() {
        breakStream(KeyNamespace.READY);

        assertThatThrownBy(() -> publisher().publish(session, instruction()).block(TIMEOUT))
                .hasMessageContaining("写 ready 失败");

        assertThat(inboxLength())
                .as("inbox 条目必须保留 —— 删掉它，客户端的重试就补不回这条消息了")
                .isEqualTo(1);
        assertThat(repository.calls())
                .as("两条命令没都成功就不落库")
                .isZero();
    }

    @Test
    @DisplayName("DLV-004 两条都成功才有回执，且回执带的是真实 replyId 与 msgSeq")
    void 两条都成功才返回回执() {
        UserInstruction instruction = instruction();

        Ack ack = publisher().publish(session, instruction).block(TIMEOUT);

        assertThat(ack).isNotNull();
        assertThat(ack.instructionId()).isEqualTo(instruction.instructionId());
        assertThat(ack.replyId()).isNotBlank();
        assertThat(inboxLength()).isEqualTo(1);
        assertThat(readyLength()).isEqualTo(1);
    }

    @Test
    @DisplayName("DLV-004 失败时不产生任何回执 —— 伪 replyId 会让客户端去等一个不存在的 turn")
    void 失败时没有回执() {
        breakStream(KeyNamespace.READY);

        Ack ack = publisher().publish(session, instruction())
                .onErrorResume(error -> reactor.core.publisher.Mono.empty())
                .block(TIMEOUT);

        assertThat(ack).isNull();
    }

    @Test
    @DisplayName("DLV-003 ready 恢复后，同 instructionId 重试能把这条消息救回来")
    void 重试可以自愈() {
        UserInstruction instruction = instruction();
        breakStream(KeyNamespace.READY);
        assertThatThrownBy(() -> publisher().publish(session, instruction).block(TIMEOUT))
                .hasMessageContaining("写 ready 失败");

        // 运维修好了 ready（或者故障自己过去了），客户端带同一个 instructionId 重试
        runtime.commands().del(KeyNamespace.READY).block(TIMEOUT);
        Ack ack = publisher().publish(session, instruction).block(TIMEOUT);

        assertThat(ack).isNotNull();
        assertThat(ack.instructionId()).isEqualTo(instruction.instructionId());
        assertThat(readyLength()).as("唤醒补回来了").isEqualTo(1);
        // inbox 里现在有两条同 instructionId 的指令 —— 这是设计允许的，
        // 重复由消息表的 (session_id, instruction_id) 唯一索引与 claimTurn 吃掉
        assertThat(inboxLength()).isEqualTo(2);
    }

    /**
     * 只记调用次数的消息表。
     *
     * <p>其余方法一律抛异常：这些用例里它们<b>本就不该被调用</b>，
     * 静默返回默认值会让"投递失败却落了库"这类顺序错误看起来一切正常。
     */
    private static final class RecordingRepository implements MessageRepository {

        private final AtomicInteger calls = new AtomicInteger();

        int calls() {
            return calls.get();
        }

        @Override
        public UserMessageOutcome appendUserMessage(SessionRef session, String replyId,
                                                    String blockId, String text,
                                                    String instructionId) {
            calls.incrementAndGet();
            ClientMessage message = ClientMessage.userText(
                    calls.get(), replyId, blockId, text, Instant.now());
            return new UserMessageOutcome(message, true);
        }

        @Override
        public long allocate(SessionRef session, int count) {
            throw new UnsupportedOperationException("本用例不该调到 allocate");
        }

        @Override
        public List<ClientMessage> append(SessionRef session, List<PendingMessage> pending) {
            throw new UnsupportedOperationException("本用例不该调到 append");
        }

        @Override
        public List<ClientMessage> since(SessionRef session, long sinceSeq, int limit) {
            throw new UnsupportedOperationException("本用例不该调到 since");
        }

        @Override
        public long lastSeq(SessionRef session) {
            throw new UnsupportedOperationException("本用例不该调到 lastSeq");
        }

        @Override
        public Optional<ClientMessage> findByInstruction(SessionRef session, String instructionId) {
            throw new UnsupportedOperationException("本用例不该调到 findByInstruction");
        }

        @Override
        public boolean claimTurn(SessionRef session, String instructionId) {
            throw new UnsupportedOperationException("本用例不该调到 claimTurn");
        }

        @Override
        public int markSuperseded(SessionRef session, String replyId) {
            throw new UnsupportedOperationException("本用例不该调到 markSuperseded");
        }
    }
}
