package io.agentharness.task;

import io.agentharness.engine.ScriptedTurnEngine;
import io.agentharness.keys.KeyNamespace;
import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.MessageRole;
import io.agentharness.protocol.SessionRef;
import io.agentharness.protocol.UserInstruction;
import io.agentharness.redis.LeaseGuard;
import io.agentharness.redis.PelHeartbeat;
import io.agentharness.redis.ReadyToken;
import io.agentharness.redis.RedisConfig;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.redis.ScriptRegistry;
import io.agentharness.redis.StreamLimits;
import io.agentharness.redis.StreamPayload;
import io.agentharness.store.datasource.DataSourceConfig;
import io.agentharness.store.datasource.SimpleDataSourceProvider;
import io.agentharness.store.eventlog.EventLogRepository;
import io.agentharness.store.jdbc.Jdbc;
import io.agentharness.store.message.MessageRepository;
import io.agentharness.store.message.PostgresMessageRepository;
import io.agentharness.store.schema.SchemaMigrator;
import io.agentharness.task.coldstore.ColdStorageBypass;
import io.agentharness.task.dispatch.ConsumerName;
import io.agentharness.task.dispatch.ReadyDispatcher;
import io.agentharness.task.dispatch.TaskTimings;
import io.agentharness.task.lease.LeaseControl;
import io.agentharness.task.outbox.OutboxStream;
import io.agentharness.task.outbox.OutboxWriter;
import io.agentharness.task.worker.ControlPublisher;
import io.agentharness.task.worker.SessionWorker;
import io.agentharness.trace.TraceSink;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisURI;
import io.lettuce.core.XReadArgs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>多个 worker 同时跑</b> —— P3 的目标就是这一句："任意 pod 处理任意 session"。
 *
 * <p>默认跳过，两个连接串都给了才跑：
 * <pre>
 * export AGENT_IT_REDIS_URI=redis://localhost:6379
 * export AGENT_IT_JDBC_URL=jdbc:postgresql://localhost:5432/agent
 * </pre>
 *
 * <h2>为什么单独有这么一个类</h2>
 * 在此之前，每个机制都是<b>单独</b>验过的：摘牌、心跳、回收、清理、闸门、交接、槽位。
 * 但"多个 worker 同时对着同一个消费组干活"这件事本身<b>一次都没跑过</b> ——
 * 而那正是所有这些机制存在的理由。组件各自正确不等于合起来正确。
 *
 * <h2>用真 PostgreSQL，不用内存替身</h2>
 * 幂等的最后一道是数据库的唯一索引与 {@code claimTurn} 的条件更新（INV-1 的兑现点）。
 * 换成内存实现就等于把要验的东西换掉了 —— 内存版的 {@code synchronized}
 * 恰好会让"两个 worker 同时认领"这件事变得不可能发生。
 *
 * <h2>Redis 连 15 号库</h2>
 * {@code ReadyDispatcher} 用的是写死的生产组名 {@code workers}。
 * 跑在默认库上会和开发机上积压的历史令牌纠缠在一起，断言全部落空。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_IT_REDIS_URI", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AGENT_IT_JDBC_URL", matches = ".+")
class MultiWorkerIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * 等待整批 turn 跑完的上限，比单条命令的 {@link #TIMEOUT} 宽得多。
     *
     * <p>36 轮推理 + 每轮的合批窗口与数据库往返，在跑<b>整个测试套件</b>时
     * （JVM 已经跑过几百个用例、GC 压力上来了）会比单独跑这一个类慢好几倍。
     * 用同一个超时值去卡两种量级完全不同的等待，得到的是一个偶发红的测试 ——
     * 而偶发红的测试用不了多久就会被当成噪音忽略掉。
     */
    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(120);
    private static final int TEST_DATABASE = 15;

    private static final int WORKERS = 3;
    private static final int SESSIONS = 12;
    private static final int TURNS_PER_SESSION = 3;

    /**
     * 显式给一组，理由与 {@code WedgedTurnRecoveryIntegrationTest} 相同：
     * 等比缩放会把 lease TTL 压到毫秒级，比一次 Redis 往返长不了多少。
     * 这里要的是 <b>MIN-IDLE 短</b>（让回收在秒级发生），同时 TTL 够长扛住真实往返。
     */
    private static final TaskTimings TIMINGS = new TaskTimings(
            Duration.ofSeconds(5),      // leaseTtl
            Duration.ofSeconds(1),      // renewInterval
            Duration.ofSeconds(2),      // reclaimMinIdle
            Duration.ofSeconds(1),      // reclaimInterval
            // 定得远长于整条用例：60 秒的话，清理任务会在断言"哪些 worker 接过活"
            // 之前就把闲下来的 consumer 元数据删掉 —— 被测系统把证据收拾干净了
            Duration.ofMinutes(30),     // consumerIdleThreshold
            Duration.ofMillis(50),      // pollInterval
            Duration.ofSeconds(2),      // shutdownGrace
            Duration.ofSeconds(20));    // maxLeaseHold

    private static RedisRuntime runtime;
    private static SimpleDataSourceProvider provider;
    private static MessageRepository repository;

    private final List<ReadyDispatcher> fleet = new ArrayList<>();
    private final List<SessionRef> sessions = new ArrayList<>();

    @BeforeAll
    static void connect() {
        RedisURI uri = RedisURI.create(System.getenv("AGENT_IT_REDIS_URI"));
        uri.setDatabase(TEST_DATABASE);
        runtime = RedisRuntime.open(RedisConfig.of(uri.toString()));

        provider = new SimpleDataSourceProvider(DataSourceConfig.of(
                System.getenv("AGENT_IT_JDBC_URL"),
                envOrDefault("AGENT_IT_DB_USER", "agent"),
                envOrDefault("AGENT_IT_DB_PASSWORD", "")));
        Jdbc jdbc = new Jdbc(provider);
        new SchemaMigrator(jdbc).migrate();
        repository = new PostgresMessageRepository(jdbc);
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @AfterAll
    static void disconnect() {
        if (runtime != null) {
            runtime.close();
        }
        if (provider != null) {
            provider.close();
        }
    }

    @BeforeEach
    void freshQueue() {
        // 15 号库归自己，清干净再开工：残留的令牌会让"每条指令恰好一个回复"无从断言
        runtime.commands().del(KeyNamespace.READY).block(TIMEOUT);
    }

    @AfterEach
    void stopFleet() {
        fleet.forEach(ReadyDispatcher::close);
        fleet.clear();
        for (SessionRef session : sessions) {
            // 七个 key 一个都不能漏 —— ctrl-stream 与 state 是 ControlPublisher 写的，
            // 漏掉它们会在测试库里越积越多（它们有 TTL，但不该指望 TTL 做清理）
            runtime.commands().del(
                    KeyNamespace.inbox(session.sessionId()),
                    KeyNamespace.cursor(session.sessionId()),
                    KeyNamespace.lease(session.sessionId()),
                    KeyNamespace.outbox(session.sessionId()),
                    KeyNamespace.ctrlStream(session.sessionId()),
                    KeyNamespace.state(session.sessionId())).block(TIMEOUT);
        }
        sessions.clear();
        runtime.commands().del(KeyNamespace.READY).block(TIMEOUT);
    }

    // ---------- 组装 ----------

    /** 起一个 worker。每个都有自己的 LeaseControl 与 ActiveTurns —— 它们模拟不同的进程。 */
    private ReadyDispatcher startWorker(String podName) {
        ScriptRegistry scripts = new ScriptRegistry(runtime);
        LeaseGuard leases = new LeaseGuard(runtime, scripts);
        leases.loadScripts().block(TIMEOUT);

        ConsumerName consumer = ConsumerName.of(podName);
        LeaseControl leaseControl = new LeaseControl(leases,
                new PelHeartbeat(runtime, ReadyDispatcher.GROUP, consumer.value()), TIMINGS);

        OutboxStream outbox = new OutboxStream(runtime, StreamLimits.OUTBOX_MAX_LEN,
                TraceSink.disabled());
        EventLogRepository coldStore = (s, replyId, type, payload) -> {
        };
        SessionWorker worker = new SessionWorker(runtime, leaseControl, repository,
                new ScriptedTurnEngine(), new OutboxWriter(repository, outbox), outbox,
                new ControlPublisher(runtime, Duration.ofMinutes(5), TraceSink.disabled()),
                new ColdStorageBypass(coldStore));

        ReadyDispatcher dispatcher = new ReadyDispatcher(
                runtime, worker, consumer, 4, leaseControl);
        dispatcher.start().block(TIMEOUT);
        fleet.add(dispatcher);
        return dispatcher;
    }

    private SessionRef newSession() {
        SessionRef session = SessionRef.of("it-user", "mw-" + UUID.randomUUID());
        sessions.add(session);
        return session;
    }

    /** 真实投递：inbox 先、ready 后（INV-1）。 */
    private void deliver(SessionRef session, String instructionId, String text) {
        runtime.commands().xadd(KeyNamespace.inbox(session.sessionId()), StreamLimits.inbox(),
                        StreamPayload.of(UserInstruction.message(instructionId, text, Instant.now())))
                .block(TIMEOUT);
        runtime.commands().xadd(KeyNamespace.READY, StreamLimits.ready(),
                StreamPayload.of(ReadyToken.of(session))).block(TIMEOUT);
    }

    /** 失败时把 Redis 侧的现场打出来 —— 光看消息表分不清"丢了"还是"卡住了"。 */
    private String redisState(SessionRef session) {
        var inbox = runtime.commands()
                .xrange(KeyNamespace.inbox(session.sessionId()), io.lettuce.core.Range.unbounded())
                .collectList().block(TIMEOUT);
        String cursor = runtime.commands()
                .hget(KeyNamespace.cursor(session.sessionId()),
                        io.agentharness.redis.Cursors.Kind.MSG.field())
                .block(TIMEOUT);
        String lease = runtime.commands()
                .get(KeyNamespace.lease(session.sessionId())).block(TIMEOUT);
        Long readyLen = runtime.commands().xlen(KeyNamespace.READY).block(TIMEOUT);
        var pending = runtime.commands()
                .xpending(KeyNamespace.READY, ReadyDispatcher.GROUP,
                        io.lettuce.core.Range.unbounded(), io.lettuce.core.Limit.from(200))
                .collectList().block(TIMEOUT);
        String slotState = fleet.stream()
                .map(d -> String.valueOf(d.inFlight()))
                .collect(Collectors.joining(","));
        return String.format("%n    现场：inbox=%s%n    cursor=%s lease=%s readyLen=%s"
                        + "%n    各 worker 在飞槽位=[" + slotState + "]（容量 4）"
                        + "%n    PEL(%d)=%s",
                inbox == null ? "?" : inbox.stream().map(e -> e.getId()).toList(),
                cursor, lease, readyLen,
                pending == null ? -1 : pending.size(),
                pending == null ? "?" : pending.stream()
                        .map(m -> m.getId() + "@" + m.getConsumer()
                                + " idle=" + m.getMsSinceLastDelivery())
                        .toList());
    }

    private List<ClientMessage> messagesOf(SessionRef session) {
        return repository.since(session, 0, 10_000);
    }

    /** 等到每个 session 都攒够了预期数量的用户消息与回复，或者超时。 */
    private void awaitReplies(int expectedTurnsPerSession) throws Exception {
        Instant deadline = Instant.now().plus(SETTLE_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            boolean allDone = sessions.stream().allMatch(session -> {
                List<ClientMessage> messages = messagesOf(session);
                return distinctAssistantReplies(messages).size() >= expectedTurnsPerSession;
            });
            if (allDone) {
                return;
            }
            Thread.sleep(200);
        }
    }

    private static Set<String> distinctAssistantReplies(List<ClientMessage> messages) {
        return messages.stream()
                .filter(m -> m.role() == MessageRole.ASSISTANT)
                .map(ClientMessage::replyId)
                .collect(Collectors.toSet());
    }

    // ---------- 断言 ----------

    /**
     * G3 的三条：<b>无消息丢失、无重复回复、无永久卡死</b>。
     * 这里把它们变成对消息表的对账。
     */
    private void assertNoDuplicatesAndNoLoss(int expectedTurns) {
        for (SessionRef session : sessions) {
            List<ClientMessage> messages = messagesOf(session);

            // ① 无消息丢失：每条指令都留下了用户消息
            assertThat(messages.stream().filter(m -> m.role() == MessageRole.USER).toList())
                    .as("session %s 的用户消息数 —— 少了就是丢消息，多了就是幂等失效。%s",
                            session.sessionId(), redisState(session))
                    .hasSize(expectedTurns);

            // ② 无重复回复：这一条<b>永远</b>是硬性的。
            //    多出来就意味着两个 worker 同时跑了同一轮（双跑），
            //    那是 lease 失效，不存在任何可接受的解释
            assertThat(distinctAssistantReplies(messages))
                    .as("session %s 的有效回复数不得超过指令数 —— 多了就是双跑。%s",
                            session.sessionId(), redisState(session))
                    .hasSizeLessThanOrEqualTo(expectedTurns);

            // ③ msgSeq 每 session 连续无洞（INV-10）—— 客户端的空窗判定依赖它
            List<Long> seqs = messages.stream().map(ClientMessage::msgSeq).sorted().toList();
            for (int i = 0; i < seqs.size(); i++) {
                assertThat(seqs.get(i))
                        .as("session %s 的 msgSeq 第 %d 项应当连续", session.sessionId(), i)
                        .isEqualTo(i + 1L);
            }
        }
    }

    /**
     * <b>重发一定有效</b> —— 这才是系统真正承诺的那条性质。
     *
     * <p>系统<b>不</b>承诺"每条投递出去的指令都必然被回答"：
     * 一轮可能因为持牌方丢牌而中途夭折，而 {@code claimTurn} 是一次性的，
     * 夭折的那一轮不会自动重跑。按既定方针，这类极端情况<b>交给用户重发</b>。
     *
     * <p>所以这里不去断言"回复数恰好等于指令数"（那是系统没做的承诺，
     * 断言它只会得到一个偶发红的测试），而是断言真正的承诺：
     * <b>把没被回答的重发一遍，一定能被处理。</b>
     * 这也正是"要避免的是用户无论怎么发消息都没人理"的可执行版本。
     */
    private void assertResendAlwaysWorks(int expectedTurns) throws Exception {
        List<SessionRef> unanswered = sessions.stream()
                .filter(s -> distinctAssistantReplies(messagesOf(s)).size() < expectedTurns)
                .toList();
        if (unanswered.isEmpty()) {
            return;
        }
        System.err.println("有 " + unanswered.size() + " 个 session 存在夭折的轮次，"
                + "按承诺它们应当能靠重发恢复");

        for (SessionRef session : unanswered) {
            int before = distinctAssistantReplies(messagesOf(session)).size();
            deliver(session, "i-resend-" + UUID.randomUUID(), "重发");

            Instant deadline = Instant.now().plus(SETTLE_TIMEOUT);
            boolean recovered = false;
            while (Instant.now().isBefore(deadline)) {
                if (distinctAssistantReplies(messagesOf(session)).size() > before) {
                    recovered = true;
                    break;
                }
                Thread.sleep(200);
            }
            assertThat(recovered)
                    .as("session %s 重发之后仍然没人理 —— 这才是真正不可接受的。%s",
                            session.sessionId(), redisState(session))
                    .isTrue();
        }
    }

    /**
     * 参与干活的 consumer 名单。
     *
     * <p>{@code XINFO CONSUMERS} 只列出<b>至少读过一次</b>的消费者，
     * 所以这份名单等于"真正接过活的 worker"。
     */
    private List<String> workedConsumers() {
        return runtime.commands()
                .xinfoConsumers(KeyNamespace.READY, ReadyDispatcher.GROUP)
                .map(io.agentharness.redis.XInfo::toFields)
                .map(fields -> io.agentharness.redis.XInfo.text(fields.get("name")))
                .collectList().block(TIMEOUT);
    }

    /**
     * 跑完之后 Redis 侧该干净：没有残留的执行权。
     *
     * <p><b>要等，不能立刻断言。</b>回复写进消息表与摘牌之间还隔着几步
     * （收尾控制帧、推进游标、再抽干一次、摘牌），最后一条回复刚落库时牌子往往还在手上。
     * 立刻断言会得到一个与被测行为无关的偶发失败 —— 而"偶尔红一次"的测试，
     * 用不了多久就会被当成噪音忽略掉。
     */
    private void awaitQueueSettled() throws Exception {
        Instant deadline = Instant.now().plus(SETTLE_TIMEOUT);
        List<String> stillHeld = List.of();
        while (Instant.now().isBefore(deadline)) {
            stillHeld = sessions.stream()
                    .filter(s -> runtime.commands().get(KeyNamespace.lease(s.sessionId()))
                            .block(TIMEOUT) != null)
                    .map(SessionRef::sessionId)
                    .toList();
            if (stillHeld.isEmpty()) {
                return;
            }
            Thread.sleep(200);
        }
        assertThat(stillHeld)
                .as("这些 session 的执行权一直没释放 —— 挂着就是永久卡死")
                .isEmpty();
    }

    // ---------- 用例 ----------

    @Test
    @DisplayName("三个 worker 同时干活：每条指令恰好一个回复，msgSeq 连续，牌子全部释放")
    void 多worker并发处理() throws Exception {
        for (int i = 1; i <= WORKERS; i++) {
            startWorker("pod-" + i);
        }
        for (int i = 0; i < SESSIONS; i++) {
            newSession();
        }

        for (int turn = 0; turn < TURNS_PER_SESSION; turn++) {
            for (SessionRef session : sessions) {
                deliver(session, "i-" + UUID.randomUUID(), "第 " + turn + " 轮");
            }
        }

        awaitReplies(TURNS_PER_SESSION);

        assertNoDuplicatesAndNoLoss(TURNS_PER_SESSION);
        assertResendAlwaysWorks(TURNS_PER_SESSION);
        awaitQueueSettled();

        // 没有这一条，"多 worker 并发"这个前提就没被验到：
        // 一个 worker 独吞全部工作时，上面所有断言<b>照样全绿</b>，
        // 而那种情况下这条用例其实什么并发问题都没测。
        //
        // 断言"至少两个"而不是"恰好三个"：抢占式队列<b>不承诺</b>均分，
        // 某个 worker 每次轮询都恰好扑空是允许的。
        // 要求恰好三个等于断言一条系统没有保证的性质，那种测试迟早会偶发红
        assertThat(workedConsumers())
                .as("至少两个 worker 真的接过活，否则这条用例测的只是单 worker")
                .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("同一 session 被并发唤醒多次，仍然只回复一次 —— lease 是排他性的唯一来源")
    void 同一session的并发唤醒不产生双跑() throws Exception {
        for (int i = 1; i <= WORKERS; i++) {
            startWorker("pod-" + i);
        }
        SessionRef session = newSession();
        String instructionId = "i-" + UUID.randomUUID();

        // 一条指令，二十个唤醒令牌 —— 模拟客户端反复重试 + 回收重投
        runtime.commands().xadd(KeyNamespace.inbox(session.sessionId()), StreamLimits.inbox(),
                        StreamPayload.of(UserInstruction.message(
                                instructionId, "只该被回答一次", Instant.now())))
                .block(TIMEOUT);
        for (int i = 0; i < 20; i++) {
            runtime.commands().xadd(KeyNamespace.READY, StreamLimits.ready(),
                    StreamPayload.of(ReadyToken.of(session))).block(TIMEOUT);
        }

        awaitReplies(1);
        // 多等一会儿：二十个令牌里剩下的那些还在被逐个消费，
        // 如果排他性有问题，重复回复会在这段时间里冒出来
        Thread.sleep(1500);

        assertNoDuplicatesAndNoLoss(1);
        assertResendAlwaysWorks(1);
        awaitQueueSettled();
    }

    @Test
    @DisplayName("令牌卡在死 pod 名下时被别的 worker 回收，并最终产生回复（RCV-009 端到端）")
    void 死pod的令牌被回收并处理() throws Exception {
        SessionRef session = newSession();
        String instructionId = "i-" + UUID.randomUUID();

        // 先建组，再让"死 pod"把令牌抢进自己的 PEL 然后什么都不做 ——
        // 这正是硬杀留下的现场：令牌已被认领、无人处理、也不会被重新投递
        startWorker("pod-1").close();
        fleet.clear();
        deliver(session, instructionId, "被死 pod 领走的活儿");
        runtime.commands().xreadgroup(
                        Consumer.from(ReadyDispatcher.GROUP, "dead-pod"),
                        XReadArgs.Builder.count(16),
                        XReadArgs.StreamOffset.lastConsumed(KeyNamespace.READY))
                .collectList().block(TIMEOUT);

        assertThat(messagesOf(session)).as("此刻还没有人处理它").isEmpty();

        // 起一个活的 worker：它的回收任务应当把令牌捞回来，走正常管道产出回复
        startWorker("pod-alive");
        awaitReplies(1);

        assertNoDuplicatesAndNoLoss(1);
        assertResendAlwaysWorks(1);
        awaitQueueSettled();
    }
}
