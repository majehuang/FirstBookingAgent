package io.agentharness.task.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 裁剪下限的纯逻辑（P4-2 / P4-4，INV-6）。
 *
 * <p>Redis 侧的实际裁剪行为由 {@code OutboxRetentionIntegrationTest} 在真 Redis 上验证，
 * 这里只钉住"下限怎么算"—— 它是 INV-6 的全部判定逻辑。
 */
class OutboxRetentionTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final long NOW = 1_725_100_000_000L;

    private final OutboxRetention retention = new OutboxRetention(WINDOW);

    @Test
    @DisplayName("无 turn 进行时，下限就是窗口起点")
    void 无turn时下限为窗口起点() {
        assertThat(retention.minId("s1", NOW))
                .isEqualTo((NOW - WINDOW.toMillis()) + "-0");
    }

    @Test
    @DisplayName("turn 首条早于窗口起点时，下限压到 turnStartId —— INV-6 的兑现点")
    void turn首条早于窗口时下限压到turnStartId() {
        // turn 已经跑了 11 分钟：首条落在窗口起点之前，纯按时间裁会把它裁掉
        String turnStart = (NOW - Duration.ofMinutes(11).toMillis()) + "-3";
        retention.beginTurn("s1", turnStart);

        assertThat(retention.minId("s1", NOW)).isEqualTo(turnStart);
    }

    @Test
    @DisplayName("turn 首条仍在窗口内时，下限维持窗口起点 —— 保护不外扩")
    void turn首条仍在窗口内时维持窗口起点() {
        String turnStart = (NOW - Duration.ofMinutes(1).toMillis()) + "-0";
        retention.beginTurn("s1", turnStart);

        assertThat(retention.minId("s1", NOW))
                .isEqualTo((NOW - WINDOW.toMillis()) + "-0");
    }

    @Test
    @DisplayName("endTurn 之后窗口恢复只按时间算")
    void endTurn后窗口恢复() {
        retention.beginTurn("s1", (NOW - Duration.ofMinutes(30).toMillis()) + "-0");
        retention.endTurn("s1");

        assertThat(retention.minId("s1", NOW))
                .isEqualTo((NOW - WINDOW.toMillis()) + "-0");
    }

    @Test
    @DisplayName("保护按 session 隔离：一个 session 的长 turn 不影响另一个的裁剪")
    void 保护按session隔离() {
        String turnStart = (NOW - Duration.ofMinutes(30).toMillis()) + "-0";
        retention.beginTurn("s1", turnStart);

        assertThat(retention.minId("s1", NOW)).isEqualTo(turnStart);
        assertThat(retention.minId("s2", NOW))
                .isEqualTo((NOW - WINDOW.toMillis()) + "-0");
    }

    @Test
    @DisplayName("同毫秒的边界取窗口起点（<ms>-0），仍在安全一侧")
    void 同毫秒边界取窗口起点() {
        long windowStart = NOW - WINDOW.toMillis();
        // turn 首条恰好落在窗口起点的同一毫秒：<ms>-0 不高于任何同毫秒条目，多留不少留
        retention.beginTurn("s1", windowStart + "-7");

        assertThat(retention.minId("s1", NOW)).isEqualTo(windowStart + "-0");
    }

    @Test
    @DisplayName("进程刚启动、now 小于窗口时下限不为负")
    void 下限不为负() {
        assertThat(new OutboxRetention(Duration.ofMinutes(10)).minId("s1", 1000L))
                .isEqualTo("0-0");
    }

    @Test
    void 毫秒前缀解析() {
        assertThat(OutboxRetention.msPart("1725100000000-5")).isEqualTo(1_725_100_000_000L);
        assertThat(OutboxRetention.msPart("1725100000000")).isEqualTo(1_725_100_000_000L);
    }

    @Test
    @DisplayName("窗口必须为正 —— 配置错误在构造时炸，不留到运行期")
    void 窗口必须为正() {
        assertThatThrownBy(() -> new OutboxRetention(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxRetention(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
