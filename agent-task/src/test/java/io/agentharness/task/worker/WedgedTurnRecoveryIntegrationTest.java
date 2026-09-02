package io.agentharness.task.worker;

import io.agentharness.engine.ScriptedTurnEngine;
import io.agentharness.engine.TurnEngine;
import io.agentharness.keys.KeyNamespace;
import io.agentharness.protocol.SessionRef;
import io.agentharness.protocol.UserInstruction;
import io.agentharness.redis.LeaseGuard;
import io.agentharness.redis.ReadyToken;
import io.agentharness.redis.RedisConfig;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.redis.ScriptRegistry;
import io.agentharness.redis.StreamPayload;
import io.agentharness.store.eventlog.EventLogRepository;
import io.agentharness.task.coldstore.ColdStorageBypass;
import io.agentharness.task.dispatch.TaskTimings;
import io.agentharness.task.lease.LeaseControl;
import io.agentharness.task.outbox.OutboxStream;
import io.agentharness.task.outbox.OutboxWriter;
import io.agentharness.trace.TraceSink;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>要避免的只有一件事：用户无论怎么发消息都没人理。</b>
 *
 * <p>默认跳过，给定 Redis 连接串才跑：
 * <pre>export AGENT_IT_REDIS_URI=redis://localhost:6379</pre>
 *
 * <h2>为什么只测这一件事</h2>
 * 单条消息滞留（投递崩在 inbox 与 ready 之间、令牌被裁掉）是<b>可以接受的</b>：
 * 用户重发一条就好了 —— 而重发之所以有效，是因为新消息会带来新的唤醒令牌，
 * 接管方抢到空闲的牌子后<b>会把之前滞留的那些一起抽干</b>。
 *
 * <p>真正致命的是<b>重发也没用</b>。推一遍就只有一个成因：
 *
 * <pre>
 * 牌子一直被占着 → 每条新消息都被判成"有人在处理"→ 交差走人
 * </pre>
 *
 * <p>而牌子占着不放只有两种可能：持有者<b>死了</b>（停止续租，TTL 30 秒自动过期，
 * 已由 lease 机制覆盖），或者持有者<b>活着但这一轮卡死</b>——
 * 续租还在一拍一拍地续，牌子因此<b>永不过期</b>。第二种就是这个类要钉住的。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_IT_REDIS_URI", matches = ".+")
class WedgedTurnRecoveryIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * 这一处<b>不能用</b> {@code scaledForTests}。
     *
     * <p>要让"持牌 15 分钟"在测试里几秒钟走完，缩放因子得接近 1000，
     * 而那会把 lease TTL 一起压到 33 毫秒 —— 比一次 Redis 往返长不了多少。
     * 于是续租还没发出去牌子就过期了，正常 turn 会被误判成"执行权丢失"，
     * <b>测试失败的原因与被测行为毫无关系</b>。
     *
     * <p>所以这里显式给一组：TTL 1s（够扛真实往返）、续租 300ms、<b>持牌上限 2s</b>。
     * 关键的那个比例（上限 &gt; TTL）保留着，构造器的校验照样跑。
     */
    private static final TaskTimings TIMINGS = new TaskTimings(
            Duration.ofSeconds(1),      // leaseTtl
            Duration.ofMillis(300),     // renewInterval
            Duration.ofMillis(600),     // reclaimMinIdle
            Duration.ofSeconds(1),      // reclaimInterval
            Duration.ofSeconds(10),     // consumerIdleThreshold
            Duration.ofMillis(50),      // pollInterval
            Duration.ofSeconds(1),      // shutdownGrace
            Duration.ofSeconds(2));     // maxLeaseHold

    private static RedisRuntime runtime;

    private final SessionRef session = SessionRef.of("it-user", "it-" + UUID.randomUUID());

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

    @AfterEach
    void cleanUp() {
        runtime.commands().del(
                KeyNamespace.inbox(session.sessionId()),
                KeyNamespace.cursor(session.sessionId()),
                KeyNamespace.lease(session.sessionId())).block(TIMEOUT);
    }

    /** 一个永远不结束的引擎 —— 这就是"进程活着但这一轮卡死"。 */
    private TurnEngine wedgedEngine() {
        return new TurnEngine() {
            @Override
            public Flux<io.agentscope.core.agent.Event> stream(SessionRef session, String text) {
                return Flux.never();
            }

            @Override
            public String engineName() {
                return "wedged";
            }

            @Override
            public void interrupt() {
            }

            @Override
            public void close() {
            }
        };
    }

    private SessionWorker worker(TurnEngine engine) {
        ScriptRegistry scripts = new ScriptRegistry(runtime);
        LeaseGuard leases = new LeaseGuard(runtime, scripts);
        leases.loadScripts().block(TIMEOUT);

        InMemoryMessageRepository repository = new InMemoryMessageRepository();
        OutboxStream outbox = new OutboxStream(runtime);
        EventLogRepository coldStore = (s, replyId, type, payload) -> {
        };
        return new SessionWorker(runtime,
                LeaseControl.withoutHeartbeat(leases, TIMINGS),
                repository, engine, new OutboxWriter(repository, outbox), outbox,
                new ControlPublisher(runtime, Duration.ofMinutes(5), TraceSink.disabled()),
                new ColdStorageBypass(coldStore));
    }

    private void deliver(String text) {
        runtime.commands().xadd(KeyNamespace.inbox(session.sessionId()),
                        StreamPayload.of(UserInstruction.message(
                                "i-" + UUID.randomUUID(), text, Instant.now())))
                .block(TIMEOUT);
    }

    @Test
    @DisplayName("卡死的 turn 会主动放弃执行权 —— 否则牌子永不过期，会话就此永久失聪")
    void 卡死的turn放弃执行权() {
        deliver("这一轮会卡住");

        // 引擎永不返回。没有持牌上限的话，续租会一直续下去，这个 block 永远不会回来
        worker(wedgedEngine()).handle(ReadyToken.of(session)).block(TIMEOUT);

        // 已经不再续租，牌子最多再活一个 TTL
        String leaseValue = runtime.commands()
                .get(KeyNamespace.lease(session.sessionId())).block(TIMEOUT);
        Long ttl = runtime.commands().pttl(KeyNamespace.lease(session.sessionId())).block(TIMEOUT);

        assertThat(ttl == null || ttl <= TIMINGS.leaseTtl().toMillis())
                .as("牌子要么已经没了，要么剩余不超过一个 TTL：value=%s pttl=%s", leaseValue, ttl)
                .isTrue();
    }

    @Test
    @DisplayName("卡死之后用户重发能被正常处理 —— 这是唯一必须成立的性质")
    void 卡死之后重发能被处理() throws Exception {
        deliver("这一轮会卡住");
        worker(wedgedEngine()).handle(ReadyToken.of(session)).block(TIMEOUT);

        // 牌子自然过期
        Thread.sleep(TIMINGS.leaseTtl().toMillis() + 300);
        assertThat(runtime.commands().get(KeyNamespace.lease(session.sessionId())).block(TIMEOUT))
                .as("牌子已经放开了").isNull();

        // 用户重发一条。接管方抢到空闲的牌子，把卡住那条与新的一起抽干
        deliver("我再发一遍");
        worker(new ScriptedTurnEngine()).handle(ReadyToken.of(session)).block(TIMEOUT);

        assertThat(runtime.commands()
                .hget(KeyNamespace.cursor(session.sessionId()),
                        io.agentharness.redis.Cursors.Kind.MSG.field())
                .block(TIMEOUT))
                .as("游标推进了 —— 两条都被处理，会话恢复可用")
                .isNotNull();
        assertThat(runtime.commands().get(KeyNamespace.lease(session.sessionId())).block(TIMEOUT))
                .as("这一轮正常收尾，牌子已摘").isNull();
    }

    @Test
    @DisplayName("正常 turn 不受影响 —— 上限的口径是「没有正常 turn 会跑这么久」")
    void 正常turn不被误伤() {
        deliver("你好");

        WorkOutcome outcome = worker(new ScriptedTurnEngine())
                .handle(ReadyToken.of(session)).block(TIMEOUT);

        assertThat(outcome).isEqualTo(WorkOutcome.COMPLETED);
        assertThat(runtime.commands().get(KeyNamespace.lease(session.sessionId())).block(TIMEOUT))
                .isNull();
    }
}
