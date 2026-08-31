package io.agentharness.task.worker;

import io.agentharness.engine.ScriptedTurnEngine;
import io.agentharness.keys.KeyNamespace;
import io.agentharness.protocol.SessionRef;
import io.agentharness.redis.LeaseGuard;
import io.agentharness.redis.ReadyToken;
import io.agentharness.redis.RedisConfig;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.redis.ScriptRegistry;
import io.agentharness.redis.StreamPayload;
import io.agentharness.store.eventlog.EventLogRepository;
import io.agentharness.task.coldstore.ColdStorageBypass;
import io.agentharness.task.dispatch.ConsumerName;
import io.agentharness.task.dispatch.ReadyDispatcher;
import io.agentharness.task.dispatch.TaskTimings;
import io.agentharness.task.lease.ActiveTurns;
import io.agentharness.task.lease.LeaseControl;
import io.agentharness.task.lease.LeaseFence;
import io.agentharness.task.outbox.OutboxStream;
import io.agentharness.task.outbox.OutboxWriter;
import io.agentharness.trace.TraceSink;
import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖 Test/P3 的 <b>CHA-009</b>（优雅停机）中调度器这一侧。
 *
 * <p>默认跳过，给定 Redis 连接串才跑：
 * <pre>export AGENT_IT_REDIS_URI=redis://localhost:6379</pre>
 *
 * <h2>这里不 {@code start()} 调度器</h2>
 * 只构造 + 停机，验的是<b>停机流程本身</b>；跑起消费循环会引入与被测行为无关的时序。
 *
 * <p><b>因此没有覆盖的部分</b>：SIGTERM 下持续投递时"不出现 5xx 风暴"、
 * 多 pod 滚动更新的用户无感知 —— 那些要真起进程，留在 P3b 的混沌套件里
 * （见 {@code todo/P3-多节点正确性/TODO.md}）。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_IT_REDIS_URI", matches = ".+")
class GracefulShutdownIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration LEASE_TTL = Duration.ofSeconds(30);


    /**
     * 连 15 号库。
     *
     * <p>{@code ReadyDispatcher.GROUP} 是写死的生产组名，而<b>消费组之间互相独立</b>：
     * 同一条 ready 上的令牌，本用例的组和生产的 {@code workers} 组<b>各收到一份</b>。
     * 于是开发者只要在本机跑着 {@code agent chat}（内嵌 worker）或 {@code agent worker}，
     * 它就会抢先拿走本用例刚投出去的 session 的执行权 ——
     * 表现是 {@code tryAcquire} 返回空、或者"牌子应当已释放"的断言失败，
     * <b>而失败原因与被测行为毫无关系</b>。
     *
     * <p>换到独立的库，测试就不再和"有人正在用这台机器"耦合。
     */
    private static final int TEST_DATABASE = 15;

    private static RedisRuntime runtime;

    /** 缩小 20 倍：宽限期 20s → 1s，其余比例关系不变。 */
    private static final TaskTimings TIMINGS = TaskTimings.scaledForTests(20);

    private LeaseControl leaseControl;
    private LeaseGuard leases;
    private ReadyDispatcher dispatcher;
    private SessionRef session;

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

    @BeforeEach
    void setUp() {
        ScriptRegistry scripts = new ScriptRegistry(runtime);
        leases = new LeaseGuard(runtime, scripts);
        leases.loadScripts().block(TIMEOUT);
        leaseControl = new LeaseControl(leases, null, TIMINGS, new ActiveTurns());

        InMemoryMessageRepository repository = new InMemoryMessageRepository();
        OutboxStream outbox = new OutboxStream(runtime, 10_000L, TraceSink.disabled());
        EventLogRepository coldStore = (s, replyId, type, payload) -> {
        };
        SessionWorker worker = new SessionWorker(runtime, leaseControl, repository,
                new ScriptedTurnEngine(), new OutboxWriter(repository, outbox), outbox,
                new ControlPublisher(runtime, Duration.ofMinutes(5), TraceSink.disabled()),
                new ColdStorageBypass(coldStore));

        dispatcher = new ReadyDispatcher(runtime, worker, ConsumerName.of("pod-shutdown-test"),
                4, leaseControl);
        session = SessionRef.of("it-user", "it-" + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        runtime.commands().del(
                KeyNamespace.lease(session.sessionId()),
                KeyNamespace.inbox(session.sessionId()),
                KeyNamespace.cursor(session.sessionId())).block(TIMEOUT);
    }

    /** 往在飞表里塞一个"跑到一半"的 turn —— Worker 平时就是这么登记的。 */
    private ActiveTurns.Handle registerInFlight() {
        runtime.commands().xadd(KeyNamespace.inbox(session.sessionId()),
                Map.of(StreamPayload.FIELD, "没跑完的活儿")).block(TIMEOUT);
        String tokenId = runtime.commands()
                .xadd(KeyNamespace.READY, StreamPayload.of(ReadyToken.of(session)))
                .block(TIMEOUT);
        LeaseGuard.Held lease = leases.tryAcquire(session, LEASE_TTL).block(TIMEOUT).orElseThrow();
        return leaseControl.activeTurns().register(session, lease, tokenId,
                new LeaseFence(session.sessionId()));
    }

    @Test
    @DisplayName("CHA-009 没有在飞任务时，停机立刻返回 —— 不空等满宽限期")
    void 空闲时停机不拖延() {
        long startedAt = System.nanoTime();

        dispatcher.shutdown().block(TIMEOUT);

        Duration spent = Duration.ofNanos(System.nanoTime() - startedAt);
        // 宽限期是等在飞任务的上限，不是固定的停机耗时。
        // 写成 sleep(grace) 的实现会在这里超出
        assertThat(spent).isLessThan(TIMINGS.shutdownGrace());
    }

    @Test
    @DisplayName("CHA-009 宽限期结束仍在飞的 turn 被主动交接，接管方立刻可接手")
    void 超时的在飞任务被交接() {
        ActiveTurns.Handle turn = registerInFlight();

        dispatcher.shutdown().block(TIMEOUT);

        assertThat(turn.fence().isLost()).as("落闸止写").isTrue();
        assertThat(runtime.commands().get(turn.lease().key()).block(TIMEOUT))
                .as("执行权已释放，接管方不必等 30 秒 TTL").isNull();
        // 重投的新令牌就在 ready 里，接管方下一次轮询（50ms）就能捞到，
        // 而不是等 MIN-IDLE 60s + 回收周期 30s
        assertThat(runtime.commands()
                .xrange(KeyNamespace.READY,
                        io.lettuce.core.Range.create("(" + turn.tokenId(), "+"))
                .collectList().block(TIMEOUT))
                .as("重投了新的唤醒令牌")
                .isNotEmpty();
    }

    @Test
    @DisplayName("停机幂等 —— shutdown hook 与 close() 可能各调一次")
    void 重复停机无害() {
        dispatcher.shutdown().block(TIMEOUT);

        assertThat(dispatcher.shutdown().block(TIMEOUT)).isNull();
        assertThat(dispatcher.inFlight()).isZero();
    }

    @Test
    @DisplayName("close() 走完整的优雅停机，不是绕过交接直接断开")
    void close走优雅路径() {
        ActiveTurns.Handle turn = registerInFlight();

        dispatcher.close();

        // close() 若只 dispose 订阅，牌子会留到 TTL 过期、令牌留到回收，
        // 也就是每次发布都让在飞 session 吃满 90 秒
        assertThat(turn.fence().isLost()).isTrue();
        assertThat(runtime.commands().get(turn.lease().key()).block(TIMEOUT)).isNull();
    }
}
