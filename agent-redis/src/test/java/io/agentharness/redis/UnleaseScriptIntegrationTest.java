package io.agentharness.redis;

import io.agentharness.keys.KeyNamespace;
import io.agentharness.protocol.SessionRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖 Test/P3 的 LUA-002～LUA-010、LSE-003、LSE-004。
 *
 * <p>默认跳过，给定 Redis 连接串才跑：
 * <pre>export AGENT_IT_REDIS_URI=redis://localhost:6379</pre>
 *
 * <p><b>这些用例不接受 mock（LUA-010）。</b>摘牌脚本的全部价值就是原子性，
 * 而原子性恰好是 mock 唯一无法验证的性质 —— mock 里"检查"和"删除"之间
 * 本来就不会有别的客户端插进来，于是非原子的实现在 mock 下也是绿的。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_IT_REDIS_URI", matches = ".+")
class UnleaseScriptIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration LEASE_TTL = Duration.ofSeconds(30);

    private static RedisRuntime runtime;

    private final List<String> touchedSessions = new ArrayList<>();

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

    /** 只清理本次用到的 key，不碰共享数据（Test/P3 README §3）。 */
    @AfterEach
    void cleanUp() {
        for (String sessionId : touchedSessions) {
            runtime.commands().del(
                    KeyNamespace.lease(sessionId),
                    KeyNamespace.inbox(sessionId),
                    KeyNamespace.cursor(sessionId)).block(TIMEOUT);
        }
        touchedSessions.clear();
    }

    private LeaseGuard newGuard() {
        ScriptRegistry scripts = new ScriptRegistry(runtime);
        LeaseGuard guard = new LeaseGuard(runtime, scripts);
        guard.loadScripts().block(TIMEOUT);
        return guard;
    }

    private SessionRef newSession() {
        SessionRef session = SessionRef.of("it-user", "it-" + UUID.randomUUID());
        touchedSessions.add(session.sessionId());
        return session;
    }

    /** 往 inbox 塞一条，返回它的 Stream ID。 */
    private String pushInbox(SessionRef session, String text) {
        return runtime.commands()
                .xadd(KeyNamespace.inbox(session.sessionId()), Map.of(StreamPayload.FIELD, text))
                .block(TIMEOUT);
    }

    private void setCursor(SessionRef session, String streamId) {
        runtime.commands()
                .hset(KeyNamespace.cursor(session.sessionId()), Cursors.Kind.MSG.field(), streamId)
                .block(TIMEOUT);
    }

    private LeaseGuard.Held acquire(LeaseGuard guard, SessionRef session) {
        Optional<LeaseGuard.Held> held = guard.tryAcquire(session, LEASE_TTL).block(TIMEOUT);
        assertThat(held).isPresent();
        return held.orElseThrow();
    }

    // ---------- 脚本加载与执行 ----------

    @Test
    @DisplayName("LUA-002/003 启动加载并缓存 SHA，运行期走 EVALSHA")
    void 启动加载运行期用SHA() {
        ScriptRegistry scripts = new ScriptRegistry(runtime);
        assertThat(scripts.digestOf(LeaseGuard.UNLEASE_SCRIPT)).isNull();

        scripts.load(LeaseGuard.UNLEASE_SCRIPT).block(TIMEOUT);
        String sha = scripts.digestOf(LeaseGuard.UNLEASE_SCRIPT);

        assertThat(sha).isNotNull().hasSize(40);
        // Redis 侧确实认识这个 SHA —— 也就是说运行期不需要再发脚本正文
        assertThat(runtime.commands().scriptExists(sha).blockFirst(TIMEOUT)).isTrue();
    }

    @Test
    @DisplayName("LUA-004 SCRIPT FLUSH 之后识别 NOSCRIPT 并重新加载，不跳过任何校验")
    void 脚本被清掉后能自愈() {
        LeaseGuard guard = newGuard();
        SessionRef session = newSession();
        LeaseGuard.Held held = acquire(guard, session);

        // 模拟 Redis 重启 / 主从切换 / 有人手工 SCRIPT FLUSH
        runtime.commands().scriptFlush().block(TIMEOUT);

        UnleaseOutcome outcome = guard.unlease(held).block(TIMEOUT);

        // 关键：自愈之后仍然是走完整脚本得出的结论，而不是退化成裸 DEL
        assertThat(outcome).isEqualTo(UnleaseOutcome.UNLEASED);
        assertThat(runtime.commands().get(held.key()).block(TIMEOUT)).isNull();
    }

    // ---------- 摘牌的三个分支 ----------

    @Test
    @DisplayName("LUA-005 持有者 + inbox 已抽干 → 摘牌，lease 消失")
    void 抽干后可以摘牌() {
        LeaseGuard guard = newGuard();
        SessionRef session = newSession();
        LeaseGuard.Held held = acquire(guard, session);

        String lastId = pushInbox(session, "第一条");
        setCursor(session, lastId);

        assertThat(guard.unlease(held).block(TIMEOUT)).isEqualTo(UnleaseOutcome.UNLEASED);
        assertThat(runtime.commands().get(held.key()).block(TIMEOUT)).isNull();
    }

    @Test
    @DisplayName("LUA-005 inbox 从未存在过也算抽干 —— 空 session 不能卡在这里")
    void 空inbox也能摘牌() {
        LeaseGuard guard = newGuard();
        SessionRef session = newSession();
        LeaseGuard.Held held = acquire(guard, session);

        assertThat(guard.unlease(held).block(TIMEOUT)).isEqualTo(UnleaseOutcome.UNLEASED);
    }

    @Test
    @DisplayName("LUA-006 token 不匹配 → 拒绝删除，别人的牌子和 TTL 都不受影响（INV-3）")
    void 非持有者不能摘牌() {
        LeaseGuard guard = newGuard();
        SessionRef session = newSession();

        LeaseGuard.Held stale = acquire(guard, session);
        // A 的牌子过期了，B 抢到新的
        runtime.commands().del(stale.key()).block(TIMEOUT);
        LeaseGuard.Held current = acquire(guard, session);

        assertThat(guard.unlease(stale).block(TIMEOUT)).isEqualTo(UnleaseOutcome.NOT_HOLDER);

        assertThat(runtime.commands().get(current.key()).block(TIMEOUT))
                .isEqualTo(current.token());
        assertThat(runtime.commands().pttl(current.key()).block(TIMEOUT)).isPositive();
    }

    @Test
    @DisplayName("LUA-007 游标之后还有条目 → 保留 lease 并返回仍有工作（INV-2）")
    void 未抽干不摘牌() {
        LeaseGuard guard = newGuard();
        SessionRef session = newSession();
        LeaseGuard.Held held = acquire(guard, session);

        String processed = pushInbox(session, "已处理");
        setCursor(session, processed);
        pushInbox(session, "还没处理");

        assertThat(guard.unlease(held).block(TIMEOUT)).isEqualTo(UnleaseOutcome.WORK_PENDING);
        assertThat(runtime.commands().get(held.key()).block(TIMEOUT)).isEqualTo(held.token());
    }

    @Test
    @DisplayName("LUA-007 游标未设置时，任何 inbox 条目都算未处理")
    void 无游标时全部算未处理() {
        LeaseGuard guard = newGuard();
        SessionRef session = newSession();
        LeaseGuard.Held held = acquire(guard, session);

        pushInbox(session, "从没被读过");

        assertThat(guard.unlease(held).block(TIMEOUT)).isEqualTo(UnleaseOutcome.WORK_PENDING);
    }

    // ---------- INV-2 专属：释放瞬间的竞争 ----------

    /**
     * 一次释放/投递竞争的完整重演。
     *
     * <p><b>三个角色缺一不可</b>，这也是为什么孤儿不能只靠"摘了牌且 inbox 非空"来判定：
     * 摘牌成功之后消息才到达是<b>安全</b>的排列 —— 投递方紧接着写的 ready 令牌
     * 会正常唤醒一个 worker。真正的孤儿需要第三个角色掺进来：
     *
     * <ol>
     *   <li><b>持牌方</b>摘牌</li>
     *   <li><b>投递方</b>写 inbox，然后写 ready（INV-1 的顺序）</li>
     *   <li><b>被唤醒的 worker</b>抢 lease；抢不到就认为"有人正在处理"而走人</li>
     * </ol>
     *
     * <p>孤儿 = 持牌方摘了牌 + 被唤醒者因为牌子还在而走人 + 消息仍未被处理。
     * 唤醒令牌已经被消费掉了，而 inbox 里那条消息再也不会有人来看。
     *
     * @param unlease 摘牌的实现。传原子脚本得到 0，传"先查后删"的反例得到非 0
     */
    private int countOrphans(LeaseGuard guard, int rounds,
                             java.util.function.Function<LeaseGuard.Held, Boolean> unlease)
            throws Exception {
        int orphans = 0;

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < rounds; i++) {
                SessionRef session = newSession();
                LeaseGuard.Held held = acquire(guard, session);

                String processed = pushInbox(session, "已处理");
                setCursor(session, processed);

                CountDownLatch gun = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                AtomicReference<Boolean> released = new AtomicReference<>(false);
                AtomicReference<Boolean> wakenSkipped = new AtomicReference<>(false);

                // ① 持牌方摘牌
                pool.execute(() -> {
                    try {
                        gun.await();
                        released.set(unlease.apply(held));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });

                // ② 投递方写 inbox，随后 ③ 被唤醒者抢牌
                pool.execute(() -> {
                    try {
                        gun.await();
                        pushInbox(session, "缝隙里到达的消息");
                        Optional<LeaseGuard.Held> taken =
                                guard.tryAcquire(session, LEASE_TTL).block(TIMEOUT);
                        if (taken.isPresent()) {
                            // 抢到了就会去抽干，那条消息有人管
                            runtime.commands().del(taken.get().key()).block(TIMEOUT);
                        } else {
                            // 抢不到 → 认为别人正在处理 → 交差走人。令牌就此消失
                            wakenSkipped.set(true);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });

                gun.countDown();
                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

                boolean hasUnprocessed = !runtime.commands()
                        .xrange(KeyNamespace.inbox(session.sessionId()),
                                io.lettuce.core.Range.create("(" + processed, "+"))
                        .collectList().block(TIMEOUT).isEmpty();

                if (released.get() && wakenSkipped.get() && hasUnprocessed) {
                    orphans++;
                }
                cleanUp();
            }
        }
        return orphans;
    }

    @Test
    @DisplayName("LUA-008 释放瞬间到达的消息不会成为孤儿 —— 原子脚本下 1000 轮为 0")
    void 释放瞬间的消息不会成为孤儿() throws Exception {
        LeaseGuard guard = newGuard();

        int orphans = countOrphans(guard, 1000,
                held -> guard.unlease(held).block(TIMEOUT) == UnleaseOutcome.UNLEASED);

        assertThat(orphans)
                .as("原子摘牌下释放瞬间到达的消息成为孤儿的次数")
                .isZero();
    }

    /**
     * CHA-002 的反证。
     *
     * <p>上一条测试跑 1000 轮真实竞争得到 0 个孤儿。但"0"有两种可能：原子性确实挡住了，
     * 或者那个竞争窗口根本没被撞上。<b>不排除第二种可能，那条测试就什么都没证明。</b>
     *
     * <p>所以这里用屏障把危险的交错<b>确定性地</b>摆出来 —— 不靠概率：
     * 让非原子实现停在"已确认 inbox 为空、还没 DEL lease"的那一刻，
     * 在这个缝隙里完整跑一遍投递与唤醒。
     */
    @Test
    @DisplayName("CHA-002 反证：非原子摘牌在缝隙里必定造出孤儿")
    void 非原子摘牌会造出孤儿() throws Exception {
        LeaseGuard guard = newGuard();
        SessionRef session = newSession();
        LeaseGuard.Held held = acquire(guard, session);

        String processed = pushInbox(session, "已处理");
        setCursor(session, processed);

        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch gapUsed = new CountDownLatch(1);
        AtomicReference<Boolean> released = new AtomicReference<>(false);

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            pool.execute(() -> {
                try {
                    // ① 先查：此刻 inbox 确实是空的
                    boolean drained = runtime.commands()
                            .xrange(KeyNamespace.inbox(session.sessionId()),
                                    io.lettuce.core.Range.create("(" + processed, "+"))
                            .collectList().block(TIMEOUT).isEmpty();
                    assertThat(drained).isTrue();
                    checked.countDown();

                    // ← 缝隙。原子脚本里不存在这个位置，这正是问题所在
                    gapUsed.await();

                    // ② 后删
                    released.set(Boolean.TRUE.equals(
                            guard.releaseIgnoringInbox(held).block(TIMEOUT)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(checked.await(10, TimeUnit.SECONDS)).isTrue();

            // 缝隙里：投递方写 inbox 并写 ready，被唤醒的 worker 来抢牌
            pushInbox(session, "缝隙里到达的消息");
            Optional<LeaseGuard.Held> woken = guard.tryAcquire(session, LEASE_TTL).block(TIMEOUT);

            // 牌子还在（DEL 尚未执行），于是被唤醒者认为"有人正在处理"，交差走人
            assertThat(woken).as("被唤醒的 worker 抢不到牌子").isEmpty();

            gapUsed.countDown();
        }

        Thread.sleep(200);

        // 结局：牌子没了、消息还在、唤醒令牌已被消费 —— 这就是孤儿
        assertThat(released.get()).as("非原子实现照样把牌子删掉了").isTrue();
        assertThat(runtime.commands().get(held.key()).block(TIMEOUT))
                .as("lease 已被删除").isNull();
        assertThat(runtime.commands()
                .xrange(KeyNamespace.inbox(session.sessionId()),
                        io.lettuce.core.Range.create("(" + processed, "+"))
                .collectList().block(TIMEOUT))
                .as("而这条消息再也不会有人来看 —— 孤儿")
                .isNotEmpty();
    }

    @Test
    @DisplayName("CHA-002 转绿：同样的状态下，原子脚本拒绝摘牌并保住牌子")
    void 原子脚本在同一状态下不摘牌() {
        LeaseGuard guard = newGuard();
        SessionRef session = newSession();
        LeaseGuard.Held held = acquire(guard, session);

        String processed = pushInbox(session, "已处理");
        setCursor(session, processed);
        // 上一条测试里"被唤醒者走人"的那一刻，Redis 的状态正是这样：
        // 牌子还在、inbox 里有一条未处理
        pushInbox(session, "缝隙里到达的消息");

        // 脚本此刻才做决定 —— 而它看到的是真实状态，不是几毫秒前的快照
        assertThat(guard.unlease(held).block(TIMEOUT)).isEqualTo(UnleaseOutcome.WORK_PENDING);
        assertThat(runtime.commands().get(held.key()).block(TIMEOUT)).isEqualTo(held.token());
    }

    // ---------- 槽约束 ----------

    @Test
    @DisplayName("LUA-009 脚本用到的三个 key 同槽，且全局 ready 不在其中")
    void 脚本的key必须同槽() {
        SessionRef session = newSession();
        String sessionId = session.sessionId();

        String lease = KeyNamespace.lease(sessionId);
        String inbox = KeyNamespace.inbox(sessionId);
        String cursor = KeyNamespace.cursor(sessionId);

        assertThat(KeyNamespace.sameSlot(lease, inbox)).isTrue();
        assertThat(KeyNamespace.sameSlot(lease, cursor)).isTrue();
        // ready 刻意不分片，传进脚本在集群版上就是 CROSSSLOT
        assertThat(KeyNamespace.sameSlot(lease, KeyNamespace.READY)).isFalse();
    }

    // ---------- 续租与释放的比值语义 ----------

    @Test
    @DisplayName("LSE-003 只有当前 token 能续租，过期 token 与别的 session 都不行")
    void 续租必须比值() {
        LeaseGuard guard = newGuard();
        SessionRef session = newSession();
        SessionRef other = newSession();

        LeaseGuard.Held stale = acquire(guard, session);
        runtime.commands().del(stale.key()).block(TIMEOUT);
        LeaseGuard.Held current = acquire(guard, session);
        LeaseGuard.Held foreign = acquire(guard, other);

        assertThat(guard.renew(current, LEASE_TTL).block(TIMEOUT)).isTrue();
        assertThat(guard.renew(stale, LEASE_TTL).block(TIMEOUT)).isFalse();

        // 别的 session 的 token 拿到这个 key 上也续不动
        LeaseGuard.Held crossed = new LeaseGuard.Held(session, current.key(), foreign.token());
        assertThat(guard.renew(crossed, LEASE_TTL).block(TIMEOUT)).isFalse();

        assertThat(runtime.commands().get(current.key()).block(TIMEOUT))
                .isEqualTo(current.token());
    }

    @Test
    @DisplayName("LSE-001/004 同 pod 两次抢占 token 不同，旧持有者删不掉新持有者的牌子")
    void 同pod两次抢占互不干扰() {
        LeaseGuard guard = newGuard();
        SessionRef session = newSession();

        LeaseGuard.Held first = acquire(guard, session);
        runtime.commands().del(first.key()).block(TIMEOUT);
        LeaseGuard.Held second = acquire(guard, session);

        // INV-3 的核心：值每次唯一。用 podName 当值的实现会在这里相等，于是互删
        assertThat(first.token()).isNotEqualTo(second.token());

        assertThat(guard.unlease(first).block(TIMEOUT)).isEqualTo(UnleaseOutcome.NOT_HOLDER);
        assertThat(runtime.commands().get(second.key()).block(TIMEOUT))
                .isEqualTo(second.token());
    }

    @Test
    @DisplayName("LSE-002 抢占用 SET NX PX：已有牌子时不覆盖、不重置 TTL")
    void 抢占不覆盖已有牌子() {
        LeaseGuard guard = newGuard();
        SessionRef session = newSession();

        LeaseGuard.Held held = acquire(guard, session);
        long ttlBefore = runtime.commands().pttl(held.key()).block(TIMEOUT);

        Optional<LeaseGuard.Held> second = guard.tryAcquire(session, LEASE_TTL).block(TIMEOUT);

        assertThat(second).isEmpty();
        assertThat(runtime.commands().get(held.key()).block(TIMEOUT)).isEqualTo(held.token());
        assertThat(runtime.commands().pttl(held.key()).block(TIMEOUT))
                .isLessThanOrEqualTo(ttlBefore);
    }
}
