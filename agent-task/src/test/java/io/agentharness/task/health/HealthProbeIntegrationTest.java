package io.agentharness.task.health;

import io.agentharness.keys.KeyNamespace;
import io.agentharness.protocol.SessionRef;
import io.agentharness.redis.ReadyToken;
import io.agentharness.redis.RedisConfig;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.redis.StreamPayload;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisURI;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 健康探针读的是不是真数。
 *
 * <p>默认跳过，给定 Redis 连接串才跑：
 * <pre>export AGENT_IT_REDIS_URI=redis://localhost:6379</pre>
 *
 * <h2>连 15 号库，而且消费组从 {@code 0} 建起</h2>
 * 都是为了让 {@code lag} 读得出来。实测过一个反直觉的点：
 * <b>在被裁剪过的共享 Stream 上，从 {@code $} 新建的消费组，{@code entries-read} 是 nil</b>，
 * 此时 lag 也可能读不出来 —— Redis 算不出来就直说，不猜。
 *
 * <p>生产的 {@code workers} 组是从 {@code 0} 建起、持续读的，lag 有值。
 * 测试要复现的是生产那种形态，不是"在共享库上临时插一个组"。
 */
@EnabledIfEnvironmentVariable(named = "AGENT_IT_REDIS_URI", matches = ".+")
class HealthProbeIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** 专用测试库：这里要独占一条干净的 ready 才能精确断言 lag。 */
    private static final int TEST_DATABASE = 15;

    private static RedisRuntime runtime;

    private String group;

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
    void createGroup() {
        group = "it-" + UUID.randomUUID();
        // 与生产一致：从 0 建起。这也是 lag 能被算出来的前提
        runtime.commands().xgroupCreate(
                        XReadArgs.StreamOffset.from(KeyNamespace.READY, "0"), group,
                        XGroupCreateArgs.Builder.mkstream())
                .block(TIMEOUT);
    }

    @AfterEach
    void destroyGroup() {
        runtime.commands().del(KeyNamespace.READY).block(TIMEOUT);
    }

    private HealthProbe probe(int inFlight, int capacity, Duration longestHeld) {
        return new HealthProbe(runtime, group,
                () -> new HealthProbe.LocalState(inFlight, capacity, longestHeld));
    }

    private void pushToken() {
        SessionRef session = SessionRef.of("it-user", "it-" + UUID.randomUUID());
        runtime.commands().xadd(KeyNamespace.READY, StreamPayload.of(ReadyToken.of(session)))
                .block(TIMEOUT);
    }

    @Test
    @DisplayName("空组：各项为 0，不误报")
    void 空组读数为零() {
        QueueHealth health = probe(0, 8, null).probe().block(TIMEOUT);

        assertThat(health.pelDepth()).isZero();
        assertThat(health.oldestPendingIdle()).isNull();
        assertThat(health.concerns(Duration.ofMinutes(15))).isEmpty();
    }

    @Test
    @DisplayName("待投递的令牌计入 lag —— 它是「有活儿没人取」的读数")
    void 未消费的令牌计入lag() {
        pushToken();
        pushToken();

        QueueHealth health = probe(0, 8, null).probe().block(TIMEOUT);

        assertThat(health.readyLag()).isEqualTo(2);
        assertThat(health.pelDepth()).isZero();
    }

    @Test
    @DisplayName("被取走未交差的令牌计入 PEL，并能读出它的 idle")
    void PEL深度与最老idle() throws Exception {
        pushToken();
        pushToken();
        runtime.commands().xreadgroup(Consumer.from(group, "pod-a"),
                        XReadArgs.StreamOffset.lastConsumed(KeyNamespace.READY))
                .collectList().block(TIMEOUT);

        Thread.sleep(400);
        QueueHealth health = probe(2, 8, Duration.ofSeconds(1)).probe().block(TIMEOUT);

        assertThat(health.pelDepth()).isEqualTo(2);
        assertThat(health.readyLag()).isZero();
        assertThat(health.consumerCount()).isEqualTo(1);
        // 这就是 XAUTOCLAIM 停跑时会一直涨的那个数
        assertThat(health.oldestPendingIdle()).isNotNull();
        assertThat(health.oldestPendingIdle().toMillis()).isGreaterThanOrEqualTo(300);
    }

    @Test
    @DisplayName("本机三个数原样带出 —— 它们只有持牌进程自己知道")
    void 本机读数由调用方提供() {
        QueueHealth health = probe(3, 8, Duration.ofMinutes(2)).probe().block(TIMEOUT);

        assertThat(health.inFlight()).isEqualTo(3);
        assertThat(health.capacity()).isEqualTo(8);
        assertThat(health.longestHeld()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("消费组不存在时降级为 -1 而不是抛错 —— 采不到指标不该影响业务")
    void 组不存在时降级() {
        HealthProbe missing = new HealthProbe(runtime, "no-such-group-" + UUID.randomUUID(),
                () -> new HealthProbe.LocalState(1, 8, null));

        QueueHealth health = missing.probe().block(TIMEOUT);

        assertThat(health).isNotNull();
        // -1 明确表示"没读到"，而不是伪造一个 0 —— 指标上的假数比缺口更难查
        assertThat(health.pelDepth()).isEqualTo(-1);
        assertThat(health.readyLag()).isEqualTo(-1);
        // 本机那部分照常可用
        assertThat(health.inFlight()).isEqualTo(1);
    }
}
