package io.agentharness.task.dispatch;

import io.agentharness.keys.KeyNamespace;
import io.agentharness.protocol.SessionRef;
import io.agentharness.redis.PelHeartbeat;
import io.agentharness.redis.ReadyToken;
import io.agentharness.redis.RedisConfig;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.redis.StreamPayload;
import io.lettuce.core.Consumer;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖 Test/P3 的 HBT-002～HBT-004、RCV-004～RCV-010、CLN-001～CLN-005、TRM-004。
 *
 * <p>默认跳过，给定 Redis 连接串才跑：
 * <pre>export AGENT_IT_REDIS_URI=redis://localhost:6379</pre>
 *
 * <h2>隔离方式</h2>
 * 每次运行用<b>独立的消费组名</b>（{@code it-<uuid>}），跑完 {@code XGROUP DESTROY}。
 * 建组与删组只影响自己那个组，不碰 ready 流里的数据，也不碰生产的 {@code workers} 组
 * （Test/P3 README §3：只清理本次 runId 命名空间，不 {@code FLUSHALL}）。
 *
 * <p>三条<b>反证</b>用例（RCV-010 / CLN-005 / TRM-004）证明的是 Redis 本身的语义，
 * 与本项目的类无关，因此它们跑在独立的测试 Stream 上 —— 其中 TRM-004 要做破坏性裁剪，
 * 绝不能落在共享的 ready 上。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_IT_REDIS_URI", matches = ".+")
class PelMaintenanceIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** 时间参数按 100 倍缩小：MIN-IDLE 60s → 600ms，比例关系原样保留。 */
    private static final TaskTimings TIMINGS = TaskTimings.scaledForTests(100);

    /**
     * 清理相关用例专用：再缩 36 倍，让 1 小时的门槛变成 1 秒。
     *
     * <p>不跟 {@link #TIMINGS} 共用一组，纯粹是因为 36 秒的等待乘以几条用例就是分钟级 ——
     * 而这几条要验的是"门槛之上/之下"的判定，与门槛具体多大无关。
     */
    private static final TaskTimings JANITOR_TIMINGS = TaskTimings.scaledForTests(3600);

    private static RedisRuntime runtime;

    private String group;

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

    /**
     * 建组的起点用 {@code $} 而不是 {@code 0}。
     *
     * <p>生产建组必须用 {@code 0}（否则冷启动会跳过已积压的令牌），但<b>测试正相反</b>：
     * ready 是共享的全局流，里面躺着历次运行留下的上百条历史条目。
     * 从 {@code 0} 起会让本用例的第一次读取捞到的全是那些历史条目，
     * 断言随后全部落空 —— 而失败信息只会说"令牌不见了"，看不出真正的原因。
     */
    @BeforeEach
    void createIsolatedGroup() {
        group = "it-" + UUID.randomUUID();
        runtime.commands().xgroupCreate(
                        XReadArgs.StreamOffset.from(KeyNamespace.READY, "$"), group,
                        XGroupCreateArgs.Builder.mkstream())
                .block(TIMEOUT);
    }

    @AfterEach
    void destroyIsolatedGroup() {
        runtime.commands().xgroupDestroy(KeyNamespace.READY, group).block(TIMEOUT);
    }

    // ---------- 工具 ----------

    private String pushToken() {
        SessionRef session = SessionRef.of("it-user", "it-" + UUID.randomUUID());
        return runtime.commands()
                .xadd(KeyNamespace.READY, StreamPayload.of(ReadyToken.of(session)))
                .block(TIMEOUT);
    }

    /** 让某个 consumer 取走全部待投递的令牌，从而在它名下形成 PEL。 */
    private List<StreamMessage<String, String>> consumeAs(String consumerName, int count) {
        return runtime.commands().xreadgroup(
                        Consumer.from(group, consumerName),
                        XReadArgs.Builder.count(count),
                        XReadArgs.StreamOffset.lastConsumed(KeyNamespace.READY))
                .collectList().block(TIMEOUT);
    }

    private PendingMessage pendingOf(String tokenId) {
        List<PendingMessage> pending = runtime.commands()
                .xpending(KeyNamespace.READY, group,
                        Range.create(tokenId, tokenId), Limit.from(1))
                .collectList().block(TIMEOUT);
        return pending.isEmpty() ? null : pending.get(0);
    }

    private long pendingCount(String consumerName) {
        return runtime.commands()
                .xpending(KeyNamespace.READY, Consumer.from(group, consumerName),
                        Range.unbounded(), Limit.from(1000))
                .count().block(TIMEOUT);
    }

    // ---------- PEL 心跳 ----------

    @Test
    @DisplayName("HBT-002/003 XCLAIM JUSTID 重置 idle，且不增加投递计数")
    void 心跳只重置idle不动投递计数() throws Exception {
        String tokenId = pushToken();
        consumeAs("pod-a", 10);

        PendingMessage before = pendingOf(tokenId);
        assertThat(before).isNotNull();
        long deliveriesBefore = before.getRedeliveryCount();

        Thread.sleep(300);
        assertThat(pendingOf(tokenId).getMsSinceLastDelivery()).isGreaterThanOrEqualTo(250);

        PelHeartbeat heartbeat = new PelHeartbeat(runtime, group, "pod-a");
        assertThat(heartbeat.touch(tokenId).block(TIMEOUT)).isTrue();

        PendingMessage after = pendingOf(tokenId);
        assertThat(after.getMsSinceLastDelivery()).isLessThan(250);
        // 不带 JUSTID 的 XCLAIM 会把它 +1 —— 一个跑十分钟的正常 turn
        // 会显示被重投了 60 次，把重试告警彻底毁掉
        assertThat(after.getRedeliveryCount()).isEqualTo(deliveriesBefore);
    }

    @Test
    @DisplayName("HBT-006 已交差的令牌不会被心跳重新变回 pending")
    void 心跳不复活已交差的令牌() {
        String tokenId = pushToken();
        consumeAs("pod-a", 10);
        runtime.commands().xack(KeyNamespace.READY, group, tokenId).block(TIMEOUT);

        PelHeartbeat heartbeat = new PelHeartbeat(runtime, group, "pod-a");

        // 不带 FORCE 的 XCLAIM 对已 ACK 的 ID 是空操作
        assertThat(heartbeat.touch(tokenId).block(TIMEOUT)).isFalse();
        assertThat(pendingOf(tokenId)).isNull();
    }

    @Test
    @DisplayName("HBT-004 有心跳的长 turn 不会被回收走")
    void 有心跳就不被回收() throws Exception {
        String tokenId = pushToken();
        consumeAs("pod-a", 10);

        PelHeartbeat heartbeat = new PelHeartbeat(runtime, group, "pod-a");
        PendingReclaimer reclaimer = new PendingReclaimer(
                runtime, group, ConsumerName.of("pod-b"), TIMINGS);

        // turn 跑了 4 个 MIN-IDLE 周期那么久，期间按心跳周期刷 idle
        for (int i = 0; i < 8; i++) {
            Thread.sleep(TIMINGS.renewInterval().toMillis());
            heartbeat.touch(tokenId).block(TIMEOUT);
            assertThat(reclaimer.reclaim(16).collectList().block(TIMEOUT))
                    .as("第 %d 拍：令牌不该被 pod-b 抢走", i)
                    .isEmpty();
        }

        assertThat(pendingOf(tokenId).getConsumer()).isEqualTo("pod-a");
    }

    // ---------- XAUTOCLAIM 回收 ----------

    @Test
    @DisplayName("RCV-004 未达到 MIN-IDLE 的令牌不被回收 —— 它可能正在被处理")
    void 未到门槛不回收() {
        String tokenId = pushToken();
        consumeAs("pod-a", 10);

        PendingReclaimer reclaimer = new PendingReclaimer(
                runtime, group, ConsumerName.of("pod-b"), TIMINGS);

        assertThat(reclaimer.reclaim(16).collectList().block(TIMEOUT)).isEmpty();
        assertThat(pendingOf(tokenId).getConsumer()).isEqualTo("pod-a");
    }

    @Test
    @DisplayName("RCV-005 达到 MIN-IDLE 的死令牌被接管")
    void 到门槛就接管() throws Exception {
        String tokenId = pushToken();
        consumeAs("pod-a", 10);

        // pod-a 死了：不再心跳，idle 自然增长
        Thread.sleep(TIMINGS.reclaimMinIdle().toMillis() + 200);

        PendingReclaimer reclaimer = new PendingReclaimer(
                runtime, group, ConsumerName.of("pod-b"), TIMINGS);
        List<StreamMessage<String, String>> claimed = reclaimer.reclaim(16)
                .collectList().block(TIMEOUT);

        assertThat(claimed).extracting(StreamMessage::getId).contains(tokenId);
        assertThat(pendingOf(tokenId).getConsumer()).isEqualTo("pod-b");
        // 回收到的是完整消息体，因此能直接进正常管道（RCV-009）
        assertThat(claimed.get(0).getBody()).containsKey(StreamPayload.FIELD);
    }

    @Test
    @DisplayName("RCV-006 循环游标直到 0-0 —— 只处理第一页会漏掉大量积压")
    void 多页回收不漏() throws Exception {
        int tokens = 40;
        for (int i = 0; i < tokens; i++) {
            pushToken();
        }
        consumeAs("pod-a", tokens);
        Thread.sleep(TIMINGS.reclaimMinIdle().toMillis() + 200);

        PendingReclaimer reclaimer = new PendingReclaimer(
                runtime, group, ConsumerName.of("pod-b"), TIMINGS);

        // 预算给足，但 XAUTOCLAIM 每次只扫一页 —— 收敛靠的是游标循环
        List<StreamMessage<String, String>> claimed = reclaimer.reclaim(tokens)
                .collectList().block(TIMEOUT);

        assertThat(claimed).hasSize(tokens);
        assertThat(claimed).extracting(StreamMessage::getId).doesNotHaveDuplicates();
        assertThat(pendingCount("pod-a")).isZero();
    }

    @Test
    @DisplayName("CAP-002 预算为 0 时一条都不领 —— 满载的 pod 不该把令牌攥在自己 PEL 里")
    void 无空闲槽位时不回收() throws Exception {
        pushToken();
        consumeAs("pod-a", 10);
        Thread.sleep(TIMINGS.reclaimMinIdle().toMillis() + 200);

        PendingReclaimer reclaimer = new PendingReclaimer(
                runtime, group, ConsumerName.of("pod-b"), TIMINGS);

        assertThat(reclaimer.reclaim(0).collectList().block(TIMEOUT)).isEmpty();
        assertThat(pendingCount("pod-a")).isEqualTo(1);
    }

    @Test
    @DisplayName("RCV-008 三个 pod 同时回收：幂等，不需要选主，令牌不会被重复派给多个 pod")
    void 并发回收不需要选主() throws Exception {
        int tokens = 12;
        for (int i = 0; i < tokens; i++) {
            pushToken();
        }
        consumeAs("dead-pod", tokens);
        Thread.sleep(TIMINGS.reclaimMinIdle().toMillis() + 200);

        List<String> claimedIds = reactor.core.publisher.Flux
                .just("pod-a", "pod-b", "pod-c")
                .flatMap(name -> new PendingReclaimer(
                        runtime, group, ConsumerName.of(name), TIMINGS).reclaim(tokens))
                .map(StreamMessage::getId)
                .collectList().block(TIMEOUT);

        // 每个令牌恰好被交出去一次：XAUTOCLAIM 本身是原子的，
        // 后到的 pod 看到的是"已经不满足 MIN-IDLE"（刚被认领，idle 归零）
        assertThat(claimedIds).hasSize(tokens).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("RCV-010 反证：关掉回收，令牌永久滞留 —— Redis 不会替你把它放回队列")
    void 不回收就永久滞留() throws Exception {
        String tokenId = pushToken();
        consumeAs("dead-pod", 10);

        // 等上好几个"扫描周期"，什么都不做
        Thread.sleep(TIMINGS.reclaimMinIdle().toMillis() * 4);

        // 官方原话：leave the messages pending forever。
        // 没有超时重投，没有错误日志，Redis 视角一切正常
        PendingMessage stuck = pendingOf(tokenId);
        assertThat(stuck).as("令牌仍卡在死 consumer 名下").isNotNull();
        assertThat(stuck.getConsumer()).isEqualTo("dead-pod");
        assertThat(runtime.commands().xreadgroup(
                        Consumer.from(group, "pod-b"),
                        XReadArgs.Builder.count(10),
                        XReadArgs.StreamOffset.lastConsumed(KeyNamespace.READY))
                .collectList().block(TIMEOUT))
                .as("正常读取看不到它 —— 这就是静默卡死的样子")
                .isEmpty();

        // 启用回收后才恢复
        Thread.sleep(TIMINGS.reclaimMinIdle().toMillis());
        List<StreamMessage<String, String>> rescued = new PendingReclaimer(
                runtime, group, ConsumerName.of("pod-b"), TIMINGS)
                .reclaim(16).collectList().block(TIMEOUT);
        assertThat(rescued).extracting(StreamMessage::getId).contains(tokenId);
    }

    // ---------- 死 consumer 清理 ----------

    @Test
    @DisplayName("CLN-003 pending > 0 时绝不 DELCONSUMER，哪怕 idle 已经很久")
    void 有pending就不清理() throws Exception {
        pushToken();
        consumeAs("dead-pod", 10);
        Thread.sleep(JANITOR_TIMINGS.consumerIdleThreshold().toMillis() + 200);

        ConsumerJanitor janitor = new ConsumerJanitor(
                runtime, group, ConsumerName.of("pod-b"), JANITOR_TIMINGS);

        assertThat(janitor.sweep().block(TIMEOUT)).isZero();
        assertThat(pendingCount("dead-pod")).isEqualTo(1);
    }

    @Test
    @DisplayName("CLN-001/004 先回收把 pending 捞空，之后才轮到清理元数据")
    void 先回收后清理() throws Exception {
        pushToken();
        consumeAs("dead-pod", 10);
        Thread.sleep(JANITOR_TIMINGS.consumerIdleThreshold().toMillis() + 200);

        ConsumerName self = ConsumerName.of("pod-b");
        ConsumerJanitor janitor = new ConsumerJanitor(runtime, group, self, JANITOR_TIMINGS);

        // 顺序反过来的话，这一步会因为 pending > 0 而跳过 dead-pod
        assertThat(janitor.sweep().block(TIMEOUT)).isZero();

        new PendingReclaimer(runtime, group, self, JANITOR_TIMINGS).reclaim(16).blockLast(TIMEOUT);
        assertThat(pendingCount("dead-pod")).isZero();

        assertThat(janitor.sweep().block(TIMEOUT)).isEqualTo(1);
        assertThat(consumerNames()).doesNotContain("dead-pod").contains("pod-b");
    }

    @Test
    @DisplayName("CLN-007 idle 未超阈值的 consumer 不会被清理")
    void 活着的consumer不被清理() {
        pushToken();
        consumeAs("busy-pod", 10);
        runtime.commands().xack(KeyNamespace.READY, group,
                pendingIds("busy-pod").toArray(String[]::new)).block(TIMEOUT);

        ConsumerJanitor janitor = new ConsumerJanitor(
                runtime, group, ConsumerName.of("pod-b"), JANITOR_TIMINGS);

        // pending 已经是 0，但 idle 远未到阈值
        assertThat(janitor.sweep().block(TIMEOUT)).isZero();
        assertThat(consumerNames()).contains("busy-pod");
    }

    @Test
    @DisplayName("CLN-004/RCV-009 一次维护循环就既捞走工作又清掉元数据 —— 顺序编码在代码里")
    void 维护循环先回收后清理() throws Exception {
        pushToken();
        consumeAs("dead-pod", 10);
        Thread.sleep(JANITOR_TIMINGS.consumerIdleThreshold().toMillis() + 200);

        ConsumerName self = ConsumerName.of("pod-b");
        MaintenanceCycle cycle = new MaintenanceCycle(
                new PendingReclaimer(runtime, group, self, JANITOR_TIMINGS),
                new ConsumerJanitor(runtime, group, self, JANITOR_TIMINGS),
                new InFlightSlots(8), JANITOR_TIMINGS);

        List<String> handled = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        cycle.runOnce(entry -> {
            handled.add(entry.getId());
            return reactor.core.publisher.Mono.empty();
        }).block(TIMEOUT);

        // 回收令牌的派发是 fire-and-forget（不能让长 turn 挂住整个维护循环）
        Thread.sleep(300);

        assertThat(handled).as("回收到的令牌进了与正常认领相同的管道").hasSize(1);
        // 清理发生在同一轮里 —— 只有"先回收把 pending 归零"才可能做到。
        // 顺序反过来的话，这一轮 dead-pod 还带着 pending，清理会跳过它
        assertThat(consumerNames()).doesNotContain("dead-pod");
    }

    @Test
    @DisplayName("RCV-002 一次回收失败不会让维护循环永久停止")
    void 回收失败不停摆() {
        ConsumerName self = ConsumerName.of("pod-b");
        // 指向一个不存在的消费组：XAUTOCLAIM 会报 NOGROUP
        MaintenanceCycle broken = new MaintenanceCycle(
                new PendingReclaimer(runtime, "no-such-group-" + UUID.randomUUID(),
                        self, JANITOR_TIMINGS),
                new ConsumerJanitor(runtime, group, self, JANITOR_TIMINGS),
                new InFlightSlots(8), JANITOR_TIMINGS);

        // 错误被记录并吞在 runOnce 里 —— 它必须正常结束，
        // 否则 schedule 的 concatMap 会因为一次抖动而整条链终止，
        // 而回收停跑正是 INV-2b 要防的那个静默故障
        assertThat(broken.runOnce(entry -> reactor.core.publisher.Mono.empty())
                .block(TIMEOUT)).isNull();
    }

    private List<String> pendingIds(String consumerName) {
        return runtime.commands()
                .xpending(KeyNamespace.READY, Consumer.from(group, consumerName),
                        Range.unbounded(), Limit.from(1000))
                .map(PendingMessage::getId)
                .collectList().block(TIMEOUT);
    }

    private List<String> consumerNames() {
        return runtime.commands().xinfoConsumers(KeyNamespace.READY, group)
                .map(io.agentharness.redis.XInfo::toFields)
                .map(fields -> String.valueOf(fields.get("name")))
                .collectList().block(TIMEOUT);
    }

    // ---------- Redis 语义的反证：独立测试 Stream ----------

    @Test
    @DisplayName("CLN-005 反证：有 pending 时 DELCONSUMER 会把工作一起销毁，且不报错")
    void 带pending删除consumer会让工作消失() {
        String stream = "it-stream-" + UUID.randomUUID();
        String isolated = "it-group";
        try {
            runtime.commands().xgroupCreate(XReadArgs.StreamOffset.from(stream, "0"), isolated,
                    XGroupCreateArgs.Builder.mkstream()).block(TIMEOUT);
            String id = runtime.commands().xadd(stream, java.util.Map.of("d", "工作")).block(TIMEOUT);
            runtime.commands().xreadgroup(Consumer.from(isolated, "victim"),
                            XReadArgs.StreamOffset.lastConsumed(stream))
                    .collectList().block(TIMEOUT);

            Long discarded = runtime.commands()
                    .xgroupDelconsumer(stream, Consumer.from(isolated, "victim")).block(TIMEOUT);

            // 命令成功返回，只在返回值里悄悄告诉你顺手扔了几条
            assertThat(discarded).as("被一并销毁的 pending 条数").isEqualTo(1);

            // 那条工作从消费组视角彻底消失：既不在 PEL，也不会被当作新条目重投
            assertThat(runtime.commands().xpending(stream, isolated,
                            Range.unbounded(), Limit.from(100))
                    .collectList().block(TIMEOUT))
                    .as("PEL 里找不到它了 —— XAUTOCLAIM 也就救不回来")
                    .isEmpty();
            assertThat(runtime.commands().xreadgroup(Consumer.from(isolated, "other"),
                            XReadArgs.StreamOffset.lastConsumed(stream))
                    .collectList().block(TIMEOUT))
                    .as("也不会被重新投递")
                    .isEmpty();
            // 实体还在 Stream 里，但没有任何消费路径能到达它
            assertThat(runtime.commands().xrange(stream, Range.create(id, id))
                    .collectList().block(TIMEOUT)).hasSize(1);
        } finally {
            runtime.commands().del(stream).block(TIMEOUT);
        }
    }

    @Test
    @DisplayName("TRM-004 反证：XTRIM 不看 PEL —— 裁掉的条目对应的工作会无声蒸发")
    void 裁剪不保护PEL() {
        String stream = "it-stream-" + UUID.randomUUID();
        String isolated = "it-group";
        try {
            runtime.commands().xgroupCreate(XReadArgs.StreamOffset.from(stream, "0"), isolated,
                    XGroupCreateArgs.Builder.mkstream()).block(TIMEOUT);

            String pendingId = runtime.commands()
                    .xadd(stream, java.util.Map.of("d", "还没处理完")).block(TIMEOUT);
            runtime.commands().xreadgroup(Consumer.from(isolated, "worker"),
                            XReadArgs.StreamOffset.lastConsumed(stream))
                    .collectList().block(TIMEOUT);

            // 积压把它挤出保留窗口
            for (int i = 0; i < 20; i++) {
                runtime.commands().xadd(stream, java.util.Map.of("d", "新工作 " + i)).block(TIMEOUT);
            }
            runtime.commands().xtrim(stream, 5L).block(TIMEOUT);

            // PEL 记录还在，指向的实体已经没了
            assertThat(runtime.commands().xpending(stream, isolated,
                            Range.create(pendingId, pendingId), Limit.from(1))
                    .collectList().block(TIMEOUT))
                    .as("PEL 记录仍然存在")
                    .hasSize(1);
            assertThat(runtime.commands().xrange(stream, Range.create(pendingId, pendingId))
                    .collectList().block(TIMEOUT))
                    .as("而实体已被裁掉 —— 回收捞回来的将是一具空壳")
                    .isEmpty();
        } finally {
            runtime.commands().del(stream).block(TIMEOUT);
        }
    }

    @Test
    @DisplayName("TRM-001 ready 的裁剪阈值集中在一处，不散落成字面量")
    void 裁剪阈值集中配置() {
        assertThat(io.agentharness.redis.StreamLimits.READY_MAX_LEN).isEqualTo(100_000L);
    }
}
