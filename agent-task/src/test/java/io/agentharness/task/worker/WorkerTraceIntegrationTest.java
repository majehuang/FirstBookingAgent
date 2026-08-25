package io.agentharness.task.worker;

import io.agentharness.engine.ScriptedTurnEngine;
import io.agentharness.keys.KeyNamespace;
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
import io.agentharness.trace.TraceStage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖 P2-9 中 worker 侧的五个环节。
 *
 * <p>默认跳过，给定 Redis 连接串才跑：
 * <pre>export AGENT_IT_REDIS_URI=redis://localhost:6379</pre>
 *
 * <p>用真 Redis 而不是打桩，是因为其中两条断言只有真 Redis 才成立：
 * ctrl 追踪里必须带着 Lua 脚本刚生成的 {@code ctrlId} 水位，
 * outbox 追踪里必须带着条目 id —— 打了桩这两样都是假的，测了等于没测。
 *
 * <p>消息表用内存实现：这里验的是埋点，不是存储。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_IT_REDIS_URI", matches = ".+")
class WorkerTraceIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static RedisRuntime runtime;

    private final SessionRef session = SessionRef.of("it-user", "it-" + UUID.randomUUID());
    private final RecordingTraceSink trace = new RecordingTraceSink();

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

    /** 装一个不碰数据库的 worker：脚本引擎 + 内存消息表 + 真 Redis。 */
    private SessionWorker worker(TraceSink sink) {
        InMemoryMessageRepository repository = new InMemoryMessageRepository();
        OutboxStream outbox = new OutboxStream(runtime, 10_000L, sink);
        EventLogRepository coldStore = (s, replyId, type, payload) -> {
        };
        return new SessionWorker(runtime, repository, new ScriptedTurnEngine(),
                new OutboxWriter(repository, outbox), outbox,
                new ControlPublisher(runtime, Duration.ofMinutes(5), sink),
                new ColdStorageBypass(coldStore), sink);
    }

    private void deliver(String text) {
        runtime.commands().xadd(KeyNamespace.inbox(session.sessionId()),
                        StreamPayload.of(UserInstruction.message(
                                "i-" + UUID.randomUUID(), text, Instant.now())))
                .block(TIMEOUT);
    }

    @Test
    @DisplayName("一轮 turn 打满 worker 侧的五个环节")
    void 五个环节全部落痕() {
        deliver("你好");

        worker(trace).handle(ReadyToken.of(session)).block(TIMEOUT);

        assertThat(trace.stages())
                .contains(TraceStage.READY_CLAIMED, TraceStage.TURN_START,
                        TraceStage.STEP_EVENT, TraceStage.CTRL_OUT, TraceStage.MESSAGE_OUT);
        assertThat(trace.entries())
                .allSatisfy(e -> assertThat(e.sessionId()).isEqualTo(session.sessionId()));
    }

    @Test
    @DisplayName("顺序是 抢牌 → turn 启动 → step —— 顺序错了说明执行权语义破了")
    void 环节顺序符合执行语义() {
        deliver("你好");

        worker(trace).handle(ReadyToken.of(session)).block(TIMEOUT);

        List<TraceStage> stages = trace.stages();
        assertThat(stages.indexOf(TraceStage.READY_CLAIMED))
                .isZero()
                .isLessThan(stages.indexOf(TraceStage.TURN_START));
        assertThat(stages.indexOf(TraceStage.TURN_START))
                .isLessThan(stages.indexOf(TraceStage.STEP_EVENT));
    }

    @Test
    @DisplayName("ctrl 追踪带着 Lua 刚生成的水位 —— 没有它就查不了重连翻转")
    void 控制追踪带出水位() {
        deliver("你好");

        worker(trace).handle(ReadyToken.of(session)).block(TIMEOUT);

        assertThat(trace.of(TraceStage.CTRL_OUT))
                .hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(e -> assertThat(e.detail()).contains("\"ctrlId\":\"").contains("-"));
    }

    @Test
    @DisplayName("outbox 追踪打的是原始消息，不是摘要")
    void 消息追踪带出原始载荷() {
        deliver("你好");

        worker(trace).handle(ReadyToken.of(session)).block(TIMEOUT);

        // 用户那条自己也要过 outbox，所以至少两条
        assertThat(trace.of(TraceStage.MESSAGE_OUT))
                .hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(e -> assertThat(e.detail())
                        .contains("\"msgSeq\":")
                        .contains("\"replyId\":"));
    }

    @Test
    @DisplayName("step 追踪压成一行 —— 一个事件刷半屏的话就没法扫整轮了")
    void 每个step一行且够短() {
        deliver("你好");

        worker(trace).handle(ReadyToken.of(session)).block(TIMEOUT);

        assertThat(trace.of(TraceStage.STEP_EVENT))
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e.detail()).doesNotContain("\n").hasSizeLessThan(200));
    }

    @Test
    @DisplayName("默认关闭 —— 热路径上不该有人为追踪买单")
    void 不传sink时零开销() {
        deliver("你好");
        InMemoryMessageRepository repository = new InMemoryMessageRepository();
        OutboxStream outbox = new OutboxStream(runtime);
        SessionWorker silent = new SessionWorker(runtime, repository, new ScriptedTurnEngine(),
                new OutboxWriter(repository, outbox), outbox, new ControlPublisher(runtime),
                new ColdStorageBypass((s, r, t, p) -> {
                }));

        silent.handle(ReadyToken.of(session)).block(TIMEOUT);

        assertThat(trace.entries()).isEmpty();
    }
}
