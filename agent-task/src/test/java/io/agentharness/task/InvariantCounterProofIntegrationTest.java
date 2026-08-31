package io.agentharness.task;

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
import io.lettuce.core.SetArgs;
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
 * 四条不变量的<b>专属反证</b>：CHA-001（INV-1）、CHA-003（INV-3）、CHA-004（INV-4）。
 *
 * <p>第四条 CHA-002（INV-2，非原子摘牌造孤儿）在
 * {@code agent-redis} 的 {@code UnleaseScriptIntegrationTest} 里 —— 它必须紧挨着
 * {@code unlease.lua} 放，那是它唯一的被测对象。
 *
 * <p>默认跳过，给定 Redis 连接串才跑：
 * <pre>export AGENT_IT_REDIS_URI=redis://localhost:6379</pre>
 *
 * <h2>反证测试的意义，以及它为什么必须成对</h2>
 * G3 要求四条不变量<b>各有一条会失败的测试</b>（先看到它红，再看到它绿）。
 * 理由很实际：一条"正确实现下没出问题"的测试，无法区分两件事 ——
 * <b>不变量真的挡住了</b>，还是<b>那个危险的交错压根没被制造出来</b>。
 * 后者是绿的，而且会一直绿到线上。
 *
 * <p>所以这里每条不变量都写成一对：先用<b>错误实现</b>把故障稳定地造出来（红），
 * 再用<b>正确实现</b>在同样的场景下证明它消失（绿）。
 * 每一对都不依赖时序运气 —— 危险的交错由测试直接摆出来，不靠并发碰运气。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_IT_REDIS_URI", matches = ".+")
class InvariantCounterProofIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration LEASE_TTL = Duration.ofSeconds(30);

    private static RedisRuntime runtime;

    private String group;
    private LeaseGuard leases;
    private SessionRef session;

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

    @BeforeEach
    void setUp() {
        group = "it-" + UUID.randomUUID();
        // 起点 $：ready 是共享流，从 0 起会先捞到历次运行的历史条目
        runtime.commands().xgroupCreate(
                        XReadArgs.StreamOffset.from(KeyNamespace.READY, "$"), group,
                        XGroupCreateArgs.Builder.mkstream())
                .block(TIMEOUT);

        ScriptRegistry scripts = new ScriptRegistry(runtime);
        leases = new LeaseGuard(runtime, scripts);
        leases.loadScripts().block(TIMEOUT);
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

    // ================================================================
    // 公共零件：投递的两条命令、以及 Worker 收尾契约的微缩版
    // ================================================================

    private String pushInbox(String text) {
        return runtime.commands().xadd(KeyNamespace.inbox(session.sessionId()),
                Map.of(StreamPayload.FIELD, text)).block(TIMEOUT);
    }

    private String pushReady() {
        return runtime.commands()
                .xadd(KeyNamespace.READY, StreamPayload.of(ReadyToken.of(session)))
                .block(TIMEOUT);
    }

    /** 取走待投递的令牌，形成 PEL。 */
    private List<StreamMessage<String, String>> claimTokens() {
        return runtime.commands().xreadgroup(
                        Consumer.from(group, "pod-a"),
                        XReadArgs.Builder.count(16),
                        XReadArgs.StreamOffset.lastConsumed(KeyNamespace.READY))
                .collectList().block(TIMEOUT);
    }

    private List<String> pendingIds() {
        return runtime.commands()
                .xpending(KeyNamespace.READY, group, Range.unbounded(), Limit.from(100))
                .map(PendingMessage::getId)
                .collectList().block(TIMEOUT);
    }

    private List<StreamMessage<String, String>> unprocessedInbox() {
        String cursor = runtime.commands()
                .hget(KeyNamespace.cursor(session.sessionId()), Cursors.Kind.MSG.field())
                .block(TIMEOUT);
        Range<String> range = cursor == null
                ? Range.unbounded()
                : Range.create("(" + cursor, "+");
        return runtime.commands().xrange(KeyNamespace.inbox(session.sessionId()), range)
                .collectList().block(TIMEOUT);
    }

    /**
     * 一次 Worker 收尾的微缩版：<b>抢牌 → 抽干（推进游标）→ 摘牌</b>。
     *
     * <p>刻意不跑推理引擎 —— 这几条反证要看的是执行权与令牌的流转，
     * 混进模型调用只会让失败原因变得难以归因。抽干与摘牌用的都是生产实现。
     *
     * @return 本次处理掉的 inbox 条目数；抢不到牌返回 -1
     */
    private int runWorkerTurn() {
        Optional<LeaseGuard.Held> held = leases.tryAcquire(session, LEASE_TTL).block(TIMEOUT);
        if (held.isEmpty()) {
            return -1;
        }
        LeaseGuard.Held lease = held.orElseThrow();

        List<StreamMessage<String, String>> entries = unprocessedInbox();
        if (!entries.isEmpty()) {
            runtime.commands().hset(KeyNamespace.cursor(session.sessionId()),
                    Cursors.Kind.MSG.field(), entries.get(entries.size() - 1).getId())
                    .block(TIMEOUT);
        }

        leases.unlease(lease).block(TIMEOUT);
        return entries.size();
    }

    // ================================================================
    // CHA-001 —— INV-1：投递顺序
    // ================================================================

    @Test
    @DisplayName("CHA-001 反证：ready 先于 inbox，唤醒被消费掉而消息成为孤儿")
    void 反序投递会丢唤醒() {
        // ① 错误顺序：先写 ready
        String token = pushReady();
        List<StreamMessage<String, String>> claimed = claimTokens();
        assertThat(claimed).extracting(StreamMessage::getId).containsExactly(token);

        // Worker 被唤醒，此刻 inbox 还是空的 —— 它正确地抽干（0 条）并摘牌
        int processed = runWorkerTurn();
        runtime.commands().xack(KeyNamespace.READY, group, token).block(TIMEOUT);

        // ② inbox 现在才写
        pushInbox("反序投递的消息");

        assertThat(processed).as("Worker 什么也没看到").isZero();
        assertThat(unprocessedInbox()).as("消息躺在 inbox 里").hasSize(1);
        assertThat(runtime.commands().get(KeyNamespace.lease(session.sessionId())).block(TIMEOUT))
                .as("执行权已释放，没人在跑").isNull();
        assertThat(pendingIds()).as("唤醒令牌已被消费并交差").isEmpty();
        assertThat(claimTokens()).as("ready 里也没有新的唤醒 —— 这条消息永远不会被处理").isEmpty();
    }

    @Test
    @DisplayName("CHA-001 转绿：inbox 先、ready 后，同一场景下消息被正常处理")
    void 正序投递不丢唤醒() {
        // ① inbox 先
        pushInbox("正序投递的消息");
        // ② ready 后
        String token = pushReady();
        claimTokens();

        int processed = runWorkerTurn();
        runtime.commands().xack(KeyNamespace.READY, group, token).block(TIMEOUT);

        assertThat(processed).as("Worker 被唤醒时消息已经在 inbox 里").isEqualTo(1);
        assertThat(unprocessedInbox()).as("没有遗留").isEmpty();
    }

    @Test
    @DisplayName("CHA-001 自愈：反序留下的孤儿，靠客户端带同一 instructionId 重试补回")
    void 反序后的重试能补回唤醒() {
        pushReady();
        claimTokens();
        runWorkerTurn();
        pushInbox("反序投递的消息");
        assertThat(unprocessedInbox()).hasSize(1);

        // 客户端没收到 202，带同一个 instructionId 重试 —— 这次两条都按顺序写成功。
        // inbox 里因此有两条（幂等由消息表的唯一索引吃掉），但唤醒补回来了
        pushInbox("反序投递的消息");
        pushReady();
        claimTokens();

        assertThat(runWorkerTurn())
                .as("重试带回了唤醒，两条都被抽走")
                .isEqualTo(2);
        assertThat(unprocessedInbox()).isEmpty();
    }

    // ================================================================
    // CHA-003 —— INV-3：lease 值每次唯一
    // ================================================================

    @Test
    @DisplayName("CHA-003 反证：拿 podName 当 lease 值，同 pod 的旧 Worker 会摘掉新 Worker 的牌")
    void 用pod名当lease值会互相释放() {
        String leaseKey = KeyNamespace.lease(session.sessionId());
        String podName = "pod-a";

        // Worker A 抢到牌，值是 podName
        assertThat(runtime.commands()
                .set(leaseKey, podName, SetArgs.Builder.nx().px(LEASE_TTL.toMillis()))
                .block(TIMEOUT)).isEqualTo("OK");

        // A 的牌过期了（长 GC / 网络卡顿），同一个 pod 上的 Worker B 抢到新的
        runtime.commands().del(leaseKey).block(TIMEOUT);
        assertThat(runtime.commands()
                .set(leaseKey, podName, SetArgs.Builder.nx().px(LEASE_TTL.toMillis()))
                .block(TIMEOUT)).isEqualTo("OK");

        // A 恢复过来，按"比值删除"释放自己的牌 —— 而两个值是一样的
        String current = runtime.commands().get(leaseKey).block(TIMEOUT);
        boolean valueMatches = podName.equals(current);
        if (valueMatches) {
            runtime.commands().del(leaseKey).block(TIMEOUT);
        }

        assertThat(valueMatches).as("比值这一步形同虚设：A 的值和 B 的值完全相同").isTrue();
        assertThat(runtime.commands().get(leaseKey).block(TIMEOUT))
                .as("B 的牌被 A 删掉了 —— B 还在跑，而执行权已经对外开放，双跑就此发生")
                .isNull();
    }

    @Test
    @DisplayName("CHA-003 转绿：值每次唯一时，同样的场景下 A 动不了 B 的牌")
    void 唯一值下旧Worker动不了新牌() {
        LeaseGuard.Held a = leases.tryAcquire(session, LEASE_TTL).block(TIMEOUT).orElseThrow();
        runtime.commands().del(a.key()).block(TIMEOUT);
        LeaseGuard.Held b = leases.tryAcquire(session, LEASE_TTL).block(TIMEOUT).orElseThrow();

        assertThat(a.token()).as("两次抢占的值不同").isNotEqualTo(b.token());

        // A 恢复后的三种动作全部被挡住
        assertThat(leases.renew(a, LEASE_TTL).block(TIMEOUT)).as("续租被拒").isFalse();
        assertThat(leases.unlease(a).block(TIMEOUT))
                .as("摘牌被拒").isEqualTo(UnleaseOutcome.NOT_HOLDER);
        assertThat(leases.releaseIgnoringInbox(a).block(TIMEOUT)).as("裸释放也被拒").isFalse();

        assertThat(runtime.commands().get(b.key()).block(TIMEOUT))
                .as("B 的牌毫发无损").isEqualTo(b.token());
    }

    // ================================================================
    // CHA-004 —— INV-4：抽干 → 摘牌 → 交差
    // ================================================================

    @Test
    @DisplayName("CHA-004 反证：先交差再摘牌，崩在中间就什么线索都不剩")
    void 先交差再摘牌的崩溃缝隙无迹可寻() {
        pushInbox("还没处理的活儿");
        String token = pushReady();
        claimTokens();
        LeaseGuard.Held lease = leases.tryAcquire(session, LEASE_TTL).block(TIMEOUT).orElseThrow();

        // ① 错误顺序：先交差
        runtime.commands().xack(KeyNamespace.READY, group, token).block(TIMEOUT);
        // ② 崩溃 —— 摘牌没跑到

        assertThat(pendingIds())
                .as("令牌已不在 PEL：XAUTOCLAIM 再也扫不到它")
                .doesNotContain(token);
        assertThat(claimTokens())
                .as("也不会被当作新条目重新投递")
                .isEmpty();
        assertThat(runtime.commands().get(lease.key()).block(TIMEOUT))
                .as("牌子还挂着，别人抢不到（要等满 TTL）")
                .isEqualTo(lease.token());
        assertThat(unprocessedInbox())
                .as("而活儿还在 inbox 里，没有任何机制知道它的存在")
                .hasSize(1);
    }

    @Test
    @DisplayName("CHA-004 转绿：抽干→摘牌→交差，崩在交差之前仍留下可回收的线索")
    void 正确顺序下崩溃仍有线索() {
        pushInbox("已处理的活儿");
        String token = pushReady();
        claimTokens();

        // ① 抽干 ② 摘牌（微缩版 Worker 做完这两步）
        assertThat(runWorkerTurn()).isEqualTo(1);
        // ③ 崩溃 —— 交差没跑到

        assertThat(runtime.commands().get(KeyNamespace.lease(session.sessionId())).block(TIMEOUT))
                .as("牌子已释放，接管方立刻能抢到").isNull();
        assertThat(pendingIds())
                .as("令牌仍在 PEL —— 这就是那条线索，回收会把它捞回来")
                .contains(token);
    }

    @Test
    @DisplayName("CHA-004 重投的令牌不会造成重复回复 —— 游标已推进，抽干为空")
    void 重投令牌不会重复处理() {
        pushInbox("已处理的活儿");
        pushReady();
        claimTokens();
        runWorkerTurn();

        // 崩溃后令牌被回收重投，接管方再跑一次同一个 session
        int reprocessed = runWorkerTurn();

        assertThat(reprocessed)
                .as("游标已经推进过，接管方抽干为空 —— 重复唤醒是安全的")
                .isZero();
    }
}
