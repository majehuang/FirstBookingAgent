package io.agentharness.task.outbox;

import io.agentharness.keys.KeyNamespace;
import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.SessionRef;
import io.agentharness.redis.RedisConfig;
import io.agentharness.redis.RedisRuntime;
import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
 * outbox 时间窗裁剪与 turnStartId 保护（P4-2 / P4-4，INV-6）。
 *
 * <p>成对写，先红后绿（与 CHA 组反证同一个理由）：
 * "正确实现下没出问题"无法区分<b>保护真的挡住了</b>与<b>裁剪压根没发生</b> ——
 * 后者也是绿的，而且会一直绿到某个长 turn 撞上窗口那天。
 * 所以反例一侧先证明：没有保护时，窗口<b>确实会</b>把进行中 turn 的前半段裁掉。
 *
 * <p>默认跳过，给定 Redis 连接串才跑：
 * <pre>export AGENT_IT_REDIS_URI=redis://localhost:6379</pre>
 */
@EnabledIfEnvironmentVariable(named = "AGENT_IT_REDIS_URI", matches = ".+")
class OutboxRetentionIntegrationTest {

    /** 连 15 号库，与其它会写共享键的集成测试同一个理由：不污染开发库。 */
    private static final int TEST_DATABASE = 15;

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** 测试窗口 300ms：让"turn 跑得比窗口长"在毫秒级就能造出来。 */
    private static final Duration WINDOW = Duration.ofMillis(300);

    private static RedisRuntime runtime;

    private final SessionRef session = SessionRef.of("it-user", "it-trim-" + UUID.randomUUID());

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

    @AfterEach
    void cleanup() {
        runtime.commands().del(KeyNamespace.outbox(session.sessionId())).block(TIMEOUT);
    }

    @Test
    @DisplayName("反证：没有 turnStartId 保护时，长 turn 的前半段被窗口裁掉 —— 重放出现空洞")
    void longTurn_outboxTrimmed_createsHole() {
        OutboxStream outbox = new OutboxStream(runtime, WINDOW, io.agentharness.trace.TraceSink.disabled());

        // 不登记 turn（相当于修复前的行为）：第一条落下后等到窗口过期，再写第二条
        outbox.publish(session, delta(1, "前半段")).block(TIMEOUT);
        sleep(WINDOW.plusMillis(200));
        outbox.publish(session, delta(2, "后半段")).block(TIMEOUT);

        List<Long> seqs = replaySeqs();
        assertThat(seqs)
                .as("反例必须真的造出空洞 —— 否则绿灯什么都证明不了")
                .containsExactly(2L);
    }

    @Test
    @DisplayName("转绿：turn 进行中窗口过期，本 turn 的条目一条不少（INV-6）")
    void turn进行中不裁剪本turn的条目() {
        OutboxStream outbox = new OutboxStream(runtime, WINDOW, io.agentharness.trace.TraceSink.disabled());

        // 同样的时序，唯一的差别是第一条经 publishTurnStart 登记了裁剪下限
        outbox.publishTurnStart(session, delta(1, "前半段")).block(TIMEOUT);
        sleep(WINDOW.plusMillis(200));
        outbox.publish(session, delta(2, "后半段")).block(TIMEOUT);
        sleep(WINDOW.plusMillis(200));
        outbox.publish(session, delta(3, "结尾")).block(TIMEOUT);

        assertThat(replaySeqs())
                .as("turn 跑了两个窗口那么久，重放仍然完整 —— 断线重连的客户端不会看到空洞")
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("endTurn 之后窗口恢复生效：上一 turn 的条目按时间裁掉")
    void endTurn之后窗口恢复生效() {
        OutboxStream outbox = new OutboxStream(runtime, WINDOW, io.agentharness.trace.TraceSink.disabled());

        outbox.publishTurnStart(session, delta(1, "上一轮")).block(TIMEOUT);
        outbox.publish(session, delta(2, "上一轮的尾巴")).block(TIMEOUT);
        outbox.endTurn(session);

        // 窗口过期后新 turn 开始：裁剪应当把上一轮清走，只留窗口内的
        sleep(WINDOW.plusMillis(200));
        outbox.publishTurnStart(session, delta(3, "新一轮")).block(TIMEOUT);

        assertThat(replaySeqs())
                .as("outbox 是重连缓冲不是历史存储 —— 超窗的部分由消息表兜底（P4-4）")
                .containsExactly(3L);
    }

    private List<Long> replaySeqs() {
        List<StreamMessage<String, String>> entries = runtime.commands()
                .xrange(KeyNamespace.outbox(session.sessionId()), Range.unbounded())
                .collectList()
                .block(TIMEOUT);
        return entries.stream()
                .map(entry -> io.agentharness.redis.StreamPayload
                        .read(entry.getBody(), ClientMessage.class).msgSeq())
                .toList();
    }

    private static ClientMessage delta(long seq, String text) {
        return ClientMessage.textDelta(seq, "r-1", "b-1", text, Instant.now());
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
