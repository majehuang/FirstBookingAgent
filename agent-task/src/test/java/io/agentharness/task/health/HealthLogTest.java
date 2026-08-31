package io.agentharness.task.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 健康输出。
 *
 * <p>这个类存在的理由本身就值得测：{@code agent-cli} 绑的是 {@code slf4j-nop}，
 * 所以 {@code log.warn} 在发布出去的二进制里什么都不输出。
 * <b>一个观测不到任何东西的观测机制，比没有更糟</b> —— 它会让"没有告警"被读成"一切正常"。
 */
class HealthLogTest {

    private static final Duration MAX_HOLD = Duration.ofMinutes(15);

    private static QueueHealth healthy() {
        return new QueueHealth(0, 1, Duration.ofSeconds(2), 2, 1, 8, Duration.ofSeconds(10));
    }

    private static QueueHealth stuck() {
        return new QueueHealth(0, 7, Duration.ofMinutes(30), 2, 0, 8, null);
    }

    @Test
    @DisplayName("健康时一个字都不打 —— 心跳日志跑久了没人看，真出问题反而淹在里面")
    void 健康时不输出() {
        AtomicInteger calls = new AtomicInteger();
        HealthLog log = (snapshot, concerns) -> calls.incrementAndGet();

        assertThat(log.report(healthy(), MAX_HOLD)).isZero();
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("有异常时逐条打出，并带上快照便于对照")
    void 异常时输出明细() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        HealthLog log = HealthLog.toStream(
                new PrintStream(buffer, true, StandardCharsets.UTF_8));

        int found = log.report(stuck(), MAX_HOLD);

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertThat(found).isEqualTo(1);
        assertThat(output)
                .contains("队列健康检查发现 1 项异常")
                .contains("PEL=7")
                .contains("回收很可能没在跑");
    }

    @Test
    @DisplayName("关闭形态什么都不做 —— 内嵌 worker 用它，免得告警盖住对话正文")
    void 关闭形态不输出() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream original = System.err;
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            HealthLog.disabled().report(stuck(), MAX_HOLD);
        } finally {
            System.setErr(original);
        }

        assertThat(buffer.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    @DisplayName("report 返回条数，调用方不必自己再判一次")
    void 返回异常条数() {
        QueueHealth manyProblems = new QueueHealth(
                999, 99, Duration.ofHours(1), 5, 8, 8, Duration.ofMinutes(14));

        assertThat(HealthLog.disabled().report(manyProblems, MAX_HOLD)).isEqualTo(3);
    }
}
