package io.agentharness.task.outbox;

import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.MessageRole;
import io.agentharness.protocol.MessageType;
import io.agentharness.protocol.SessionRef;
import io.agentharness.store.message.MessageRepository;
import io.agentharness.store.message.PendingMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import io.agentharness.keys.KeyNamespace;
import io.agentharness.redis.RedisConfig;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.trace.TraceSink;
import io.lettuce.core.RedisURI;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 合批写入的背压。
 *
 * <p>这个类来自一次真实调试现场：
 *
 * <pre>
 * ← outbox … {"type":"ERROR","fallbackText":"Could not emit buffer due to lack of requests"}
 * ⇄ ctrl   … {"phase":"FAILED","turnActive":false}
 * </pre>
 *
 * <p>用户看到的是一条报错回复，而这一轮的内容本来是好的。
 *
 * <h2>成因</h2>
 * {@code bufferTimeout} 是<b>定时推送</b>：每个窗口往下游推一批，不看下游要不要。
 * {@code concatMap} 是<b>拉取</b>：一次只要一个。
 * 落库 + {@code XADD} 一旦慢过合批窗口，批次就在中间堆积，
 * {@code bufferTimeout} 攒不下便抛 {@code OverflowException}，<b>整轮失败</b>。
 *
 * <p>与 {@code Periodic} 挡的是同一类坑（那两处是 {@code Flux.interval} 的
 * {@code Could not emit tick}），只是这一处在热路径上，代价从「后台循环停跑」
 * 升级成「用户的回复变成一条报错」。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_IT_REDIS_URI", matches = ".+")
class OutboxBackpressureTest {

    /** 连 15 号库：这条用例会往 outbox 里灌两百条，不该落在共享库上。 */
    private static final int TEST_DATABASE = 15;

    private static RedisRuntime runtime;

    @BeforeAll
    static void connect() {
        RedisURI uri = RedisURI.create(System.getenv("AGENT_IT_REDIS_URI"));
        uri.setDatabase(TEST_DATABASE);
        runtime = RedisRuntime.open(RedisConfig.of(uri.toString()));
    }

    @AfterAll
    static void disconnect() {
        if (runtime != null) {
            runtime.commands().del(KeyNamespace.outbox(SESSION.sessionId()))
                    .block(Duration.ofSeconds(10));
            runtime.close();
        }
    }


    private static final SessionRef SESSION = SessionRef.of("u", "s-1");

    /** 合批窗口 20ms，而每批落库要 120ms —— 稳定造出「下游跟不上」。 */
    private static final Duration WINDOW = Duration.ofMillis(20);
    private static final Duration SLOW_WRITE = Duration.ofMillis(120);

    @Test
    @DisplayName("反证：bufferTimeout 直接接 concatMap，落库一慢就整轮报错")
    void 裸bufferTimeout会被慢落库打死() {
        AtomicReference<Throwable> death = new AtomicReference<>();

        // 这就是修复前的写法：定时推送直接接拉取型下游
        try {
            Flux.range(1, 200)
                    .delayElements(Duration.ofMillis(5))
                    .bufferTimeout(64, WINDOW)
                    .concatMap(batch -> Mono.delay(SLOW_WRITE).thenReturn(batch))
                    .blockLast(Duration.ofSeconds(20));
        } catch (RuntimeException e) {
            death.set(e);
        }

        assertThat(death.get())
                .as("裸写法确实会死 —— 这条反证不成立的话，下面的修复就没有意义")
                .isNotNull()
                .hasMessageContaining("Could not emit buffer");
    }

    @Test
    @DisplayName("转绿：OutboxWriter 在同样的慢落库下，一条消息都不丢")
    void 慢落库下不丢消息且不报错() {
        int drafts = 200;
        RecordingRepository repository = new RecordingRepository(SLOW_WRITE);
        OutboxStream outbox = new OutboxStream(runtime, 10_000L, TraceSink.disabled());
        OutboxWriter writer = new OutboxWriter(repository, outbox, 64, WINDOW);

        List<ClientMessage> written = writer
                .write(SESSION, Flux.range(1, drafts)
                        .delayElements(Duration.ofMillis(5))
                        .map(OutboxBackpressureTest::draft))
                .collectList()
                .block(Duration.ofSeconds(60));

        assertThat(written)
                .as("落库比合批窗口慢六倍，但一条都不能少 —— 丢了就是违反 INV-5")
                .hasSize(drafts);
        // 顺序也必须原样保留（INV-8）
        assertThat(written.stream().map(ClientMessage::fallbackText).toList())
                .isEqualTo(Flux.range(1, drafts).map(String::valueOf).collectList().block());
    }

    private static PendingMessage draft(int i) {
        return new PendingMessage("r-1", "b-" + i, MessageRole.ASSISTANT,
                MessageType.TEXT_DELTA, String.valueOf(i), Map.of(), Instant.now());
    }

    /** 落库慢得离谱的消息表。 */
    private record RecordingRepository(Duration cost) implements MessageRepository {

        @Override
        public List<ClientMessage> append(SessionRef session, List<PendingMessage> pending) {
            try {
                Thread.sleep(cost.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            List<ClientMessage> out = new ArrayList<>(pending.size());
            for (PendingMessage p : pending) {
                out.add(new ClientMessage(out.size() + 1L, p.replyId(), p.blockId(), p.role(),
                        p.type(), p.fallbackText(), p.payload(), p.createdAt()));
            }
            return out;
        }

        @Override
        public long allocate(SessionRef session, int count) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserMessageOutcome appendUserMessage(SessionRef s, String r, String b, String t,
                                                    String i) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ClientMessage> since(SessionRef session, long sinceSeq, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long lastSeq(SessionRef session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ClientMessage> findByInstruction(SessionRef s, String instructionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean claimTurn(SessionRef session, String instructionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int markSuperseded(SessionRef session, String replyId) {
            throw new UnsupportedOperationException();
        }
    }
}
