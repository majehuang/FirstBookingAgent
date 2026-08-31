package io.agentharness.task.lease;

import io.agentharness.keys.KeyNamespace;
import io.agentharness.protocol.SessionRef;
import io.agentharness.redis.Cursors;
import io.agentharness.redis.LeaseGuard;
import io.agentharness.redis.ReadyToken;
import io.agentharness.redis.RedisConfig;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.redis.ScriptRegistry;
import io.agentharness.redis.StreamPayload;
import io.agentharness.redis.UnleaseOutcome;
import io.lettuce.core.Consumer;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisURI;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.models.stream.PendingMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖 Test/P3 的 <b>CHA-009</b>（优雅停机对用户无感知）里的交接部分。
 *
 * <p>默认跳过，给定 Redis 连接串才跑：
 * <pre>export AGENT_IT_REDIS_URI=redis://localhost:6379</pre>
 *
 * <p>验的核心是一句话：<b>交接之后，别的 pod 立刻就能接手，不必等 90 秒。</b>
 */
@EnabledIfEnvironmentVariable(named = "AGENT_IT_REDIS_URI", matches = ".+")
class TurnHandoffIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
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

    private String group;
    private LeaseGuard leases;
    private TurnHandoff handoff;
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
        group = "it-" + UUID.randomUUID();
        // 起点用 $：ready 是共享流，从 0 起会先捞到历次运行的上百条历史条目
        runtime.commands().xgroupCreate(
                        XReadArgs.StreamOffset.from(KeyNamespace.READY, "$"), group,
                        XGroupCreateArgs.Builder.mkstream())
                .block(TIMEOUT);

        ScriptRegistry scripts = new ScriptRegistry(runtime);
        leases = new LeaseGuard(runtime, scripts);
        leases.loadScripts().block(TIMEOUT);
        handoff = new TurnHandoff(runtime, leases, group);
        session = SessionRef.of("it-user", "it-" + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        runtime.commands().xgroupDestroy(KeyNamespace.READY, group).block(TIMEOUT);
        runtime.commands().del(
                KeyNamespace.lease(session.sessionId()),
                KeyNamespace.inbox(session.sessionId()),
                KeyNamespace.cursor(session.sessionId())).block(TIMEOUT);
    }

    /** 造一个"跑到一半"的 turn：inbox 里有没处理完的活儿，牌子在手，令牌在自己 PEL 里。 */
    private ActiveTurns.Handle inFlightTurn() {
        runtime.commands().xadd(KeyNamespace.inbox(session.sessionId()),
                Map.of(StreamPayload.FIELD, "没跑完的活儿")).block(TIMEOUT);

        String tokenId = runtime.commands()
                .xadd(KeyNamespace.READY, StreamPayload.of(ReadyToken.of(session)))
                .block(TIMEOUT);
        runtime.commands().xreadgroup(Consumer.from(group, "dying-pod"),
                        XReadArgs.StreamOffset.lastConsumed(KeyNamespace.READY))
                .collectList().block(TIMEOUT);

        LeaseGuard.Held lease = leases.tryAcquire(session, LEASE_TTL).block(TIMEOUT).orElseThrow();
        return new ActiveTurns().register(session, lease, tokenId,
                new LeaseFence(session.sessionId()));
    }

    @Test
    @DisplayName("CHA-009 交接后牌子free、新令牌就位、旧令牌已交差")
    void 交接四步都发生() {
        ActiveTurns.Handle turn = inFlightTurn();

        handoff.handOff(turn).block(TIMEOUT);

        // ① 落闸：本 pod 从此不再是合法写入者
        assertThat(turn.fence().isLost()).isTrue();
        // ② 牌子已释放，接管方可以立刻抢到
        assertThat(runtime.commands().get(turn.lease().key()).block(TIMEOUT)).isNull();
        // ④ 旧令牌已交差，不会在 60 秒后被回收成一次多余唤醒
        assertThat(pendingIds()).doesNotContain(turn.tokenId());
    }

    @Test
    @DisplayName("CHA-009 交接后另一个 pod 立刻就能接手 —— 不必等 90 秒回收")
    void 接管方立刻可以接手() {
        ActiveTurns.Handle turn = inFlightTurn();

        handoff.handOff(turn).block(TIMEOUT);

        // 接管方的视角：一次普通的 XREADGROUP 就拿到了新令牌
        List<StreamMessage<String, String>> claimed = runtime.commands()
                .xreadgroup(Consumer.from(group, "taking-over-pod"),
                        XReadArgs.Builder.count(16),
                        XReadArgs.StreamOffset.lastConsumed(KeyNamespace.READY))
                .collectList().block(TIMEOUT);

        assertThat(claimed).as("新令牌立刻可读，不需要经过 XAUTOCLAIM").hasSize(1);
        assertThat(StreamPayload.read(claimed.get(0).getBody(), ReadyToken.class).sessionId())
                .isEqualTo(session.sessionId());
        assertThat(claimed.get(0).getId()).isNotEqualTo(turn.tokenId());

        // 而且牌子确实抢得到 —— 释放排在重投之前，所以不存在"扑空"
        Optional<LeaseGuard.Held> taken = leases.tryAcquire(session, LEASE_TTL).block(TIMEOUT);
        assertThat(taken).isPresent();
        leases.releaseIgnoringInbox(taken.orElseThrow()).block(TIMEOUT);
    }

    @Test
    @DisplayName("交接不吞未处理的消息 —— inbox 原样留给接管方")
    void 未处理的消息完整保留() {
        ActiveTurns.Handle turn = inFlightTurn();

        handoff.handOff(turn).block(TIMEOUT);

        assertThat(runtime.commands()
                .xrange(KeyNamespace.inbox(session.sessionId()), Range.unbounded())
                .collectList().block(TIMEOUT))
                .as("交接只让出执行权，不动 inbox")
                .hasSize(1);
    }

    @Test
    @DisplayName("这正是 unlease 拒绝的局面 —— 交接靠重投令牌补上唤醒，不是靠绕过 INV-2")
    void 摘牌在此局面下会拒绝() {
        ActiveTurns.Handle turn = inFlightTurn();

        // inbox 非空，摘牌脚本正确地拒绝释放。交接不是"绕过检查"，
        // 而是"释放 + 立刻重建唤醒"，孤儿的成因因此不成立
        assertThat(leases.unlease(turn.lease()).block(TIMEOUT))
                .isEqualTo(UnleaseOutcome.WORK_PENDING);
        assertThat(runtime.commands().get(turn.lease().key()).block(TIMEOUT))
                .isEqualTo(turn.lease().token());
    }

    @Test
    @DisplayName("游标未推进 —— 接管方会从同一条指令重新开始，不跳过任何东西")
    void 交接不推进游标() {
        ActiveTurns.Handle turn = inFlightTurn();

        handoff.handOff(turn).block(TIMEOUT);

        assertThat(runtime.commands()
                .hget(KeyNamespace.cursor(session.sessionId()), Cursors.Kind.MSG.field())
                .block(TIMEOUT))
                .as("交接方没跑完就不该推进游标（LSE-009）")
                .isNull();
    }

    @Test
    @DisplayName("牌子已被他人抢走时交接不炸 —— 停机路径上任何一条抛出都会连累后面的 turn")
    void 牌子已易主时安全退出() {
        ActiveTurns.Handle turn = inFlightTurn();
        // 模拟"续租早就失败了"：牌子已经不是我们的
        runtime.commands().del(turn.lease().key()).block(TIMEOUT);

        handoff.handOff(turn).block(TIMEOUT);

        assertThat(turn.fence().isLost()).isTrue();
        assertThat(pendingIds()).doesNotContain(turn.tokenId());
    }

    private List<String> pendingIds() {
        return runtime.commands()
                .xpending(KeyNamespace.READY, group, Range.unbounded(), Limit.from(100))
                .map(PendingMessage::getId)
                .collectList().block(TIMEOUT);
    }
}
