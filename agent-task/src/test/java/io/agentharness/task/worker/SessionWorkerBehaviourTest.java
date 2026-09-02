package io.agentharness.task.worker;

import io.agentharness.engine.ScriptedTurnEngine;
import io.agentharness.engine.TurnEngine;
import io.agentharness.keys.KeyNamespace;
import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.MessageRole;
import io.agentharness.protocol.SessionRef;
import io.agentharness.protocol.UserInstruction;
import io.agentharness.redis.ReadyToken;
import io.agentharness.redis.RedisConfig;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.redis.StreamPayload;
import io.agentharness.store.eventlog.EventLogRepository;
import io.agentharness.task.coldstore.ColdStorageBypass;
import io.agentharness.task.outbox.OutboxStream;
import io.agentharness.task.outbox.OutboxWriter;
import io.agentharness.trace.TraceSink;
import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * turn 生命周期在 Redis 路径上的行为。
 *
 * <p>这些断言原先挂在 {@code AgentScopeBackendTest} 上 —— 那是进程内直连的后端，
 * 已随"内嵌 worker 也走 Redis"一起删掉。同一套语义现在只剩 {@link SessionWorker}
 * 一份实现，断言也就该跟到这里来：删实现不该顺手删掉它守着的行为。
 *
 * <p>默认跳过，给定 Redis 连接串才跑：
 * <pre>export AGENT_IT_REDIS_URI=redis://localhost:6379</pre>
 */
@EnabledIfEnvironmentVariable(named = "AGENT_IT_REDIS_URI", matches = ".+")
class SessionWorkerBehaviourTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static RedisRuntime runtime;

    private final InMemoryMessageRepository repository = new InMemoryMessageRepository();
    private final RecordingTurnLog turnLog = new RecordingTurnLog();
    private final TurnEngine engine = new ScriptedTurnEngine();

    @BeforeAll
    static void connect() {
        runtime = RedisRuntime.open(RedisConfig.of(System.getenv("AGENT_IT_REDIS_URI")));
    }

    @AfterAll
    static void disconnect() {
        if (runtime != null) {
            runtime.close();
        }
    }

    private SessionRef newSession() {
        return SessionRef.of("it-user", "it-" + UUID.randomUUID());
    }

    /** 一个 worker 实例，服务所有 session —— 引擎是无状态的，没有实例缓存那一层。 */
    private SessionWorker worker() {
        OutboxStream outbox = new OutboxStream(runtime);
        EventLogRepository coldStore = (s, replyId, type, payload) -> {
        };
        return new SessionWorker(runtime, TestLeases.control(runtime), repository, engine,
                new OutboxWriter(repository, outbox), outbox,
                new ControlPublisher(runtime, Duration.ofMinutes(5), TraceSink.disabled()),
                new ColdStorageBypass(coldStore), TraceSink.disabled(), turnLog);
    }

    private String deliver(SessionRef session, String text) {
        String instructionId = "i-" + UUID.randomUUID();
        deliver(session, text, instructionId);
        return instructionId;
    }

    private void deliver(SessionRef session, String text, String instructionId) {
        runtime.commands().xadd(KeyNamespace.inbox(session.sessionId()),
                        StreamPayload.of(UserInstruction.message(instructionId, text, Instant.now())))
                .block(TIMEOUT);
    }

    private List<ClientMessage> outboxOf(SessionRef session) {
        List<StreamMessage<String, String>> entries = runtime.commands()
                .xrange(KeyNamespace.outbox(session.sessionId()), Range.unbounded())
                .collectList().block(TIMEOUT);
        List<ClientMessage> messages = new ArrayList<>();
        if (entries != null) {
            entries.forEach(entry ->
                    messages.add(StreamPayload.read(entry.getBody(), ClientMessage.class)));
        }
        return messages;
    }

    @Test
    @DisplayName("一个 worker 服务多个会话 —— 引擎无状态，没有 per-session 实例")
    void 同一个worker服务多个会话() {
        SessionRef first = newSession();
        SessionRef second = newSession();
        deliver(first, "第一个会话");
        deliver(second, "第二个会话");

        SessionWorker worker = worker();
        worker.handle(ReadyToken.of(first)).block(TIMEOUT);
        worker.handle(ReadyToken.of(second)).block(TIMEOUT);

        assertThat(outboxOf(first)).isNotEmpty();
        assertThat(outboxOf(second)).isNotEmpty();
    }

    @Test
    @DisplayName("会话之间的出站流互不串扰 —— 串了的话另一个人会看到你的对话")
    void 每个会话有独立的出站流() {
        SessionRef first = newSession();
        SessionRef second = newSession();
        deliver(first, "只属于第一个会话");
        deliver(second, "只属于第二个会话");

        SessionWorker worker = worker();
        worker.handle(ReadyToken.of(first)).block(TIMEOUT);
        worker.handle(ReadyToken.of(second)).block(TIMEOUT);

        assertThat(outboxOf(first)).extracting(ClientMessage::fallbackText)
                .anySatisfy(text -> assertThat(text).contains("只属于第一个会话"))
                .noneSatisfy(text -> assertThat(text).contains("只属于第二个会话"));
    }

    @Test
    @DisplayName("用户消息排在助手输出之前，且在同一个 seq 空间里")
    void 用户消息先于助手输出() {
        SessionRef session = newSession();
        deliver(session, "你好");

        worker().handle(ReadyToken.of(session)).block(TIMEOUT);

        List<ClientMessage> messages = outboxOf(session);
        assertThat(messages).isNotEmpty();
        assertThat(messages.get(0).role()).isEqualTo(MessageRole.USER);
        // 一套顺序来源：seq 严格递增，用户自己的话也在这条序列里
        assertThat(messages).extracting(ClientMessage::msgSeq).isSorted();
    }

    @Test
    @DisplayName("同一 instructionId 重投只跑一轮 —— 客户端超时重试不该产生第二份回复")
    void 重复投递命中幂等() {
        SessionRef session = newSession();
        String instructionId = "i-" + UUID.randomUUID();
        deliver(session, "你好", instructionId);
        deliver(session, "你好", instructionId);

        worker().handle(ReadyToken.of(session)).block(TIMEOUT);

        // 两条 inbox 条目都被抽干，但只认领到一次，因此只有一轮日志。
        // 一轮里的多条消息共用同一个 replyId，所以要断言的是"只有一个 replyId"，
        // 不是"没有重复的 replyId"
        assertThat(turnLog.summaries()).hasSize(1);
        assertThat(outboxOf(session)).extracting(ClientMessage::replyId)
                .containsOnly(turnLog.summaries().get(0).replyId());
    }

    @Test
    @DisplayName("每轮打一行日志，带引擎名与计数")
    void 每轮一行日志() {
        SessionRef session = newSession();
        deliver(session, "你好");

        worker().handle(ReadyToken.of(session)).block(TIMEOUT);

        assertThat(turnLog.summaries()).hasSize(1);
        TurnLog.TurnSummary summary = turnLog.summaries().get(0);
        assertThat(summary.succeeded()).isTrue();
        assertThat(summary.sessionId()).isEqualTo(session.sessionId());
        // 可控引擎的名字带着"不调模型"的自述，前缀对上即可
        assertThat(summary.engineName()).startsWith("scripted");
        assertThat(summary.events()).isPositive();
        assertThat(summary.messages()).isPositive();
    }

    @Test
    @DisplayName("引擎失败时也要打一行 —— 静悄悄的失败是最难查的那种")
    void 失败也打一行() {
        SessionRef session = newSession();
        // ScriptedTurnEngine 的 !error 前缀：先产出内容再失败
        deliver(session, "!error");

        worker().handle(ReadyToken.of(session)).block(TIMEOUT);

        assertThat(turnLog.summaries()).hasSize(1);
        assertThat(turnLog.summaries().get(0).succeeded()).isFalse();
        assertThat(turnLog.summaries().get(0).failure()).isNotBlank();
    }

    /** 把每轮的日志收起来断言，而不是打到 stdout。 */
    private static final class RecordingTurnLog implements TurnLog {

        private final List<TurnSummary> summaries =
                java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void turnFinished(TurnSummary summary) {
            summaries.add(summary);
        }

        List<TurnSummary> summaries() {
            return List.copyOf(summaries);
        }
    }
}
