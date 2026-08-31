package io.agentharness.task.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 告警规则 —— 纯逻辑，不需要 Redis。
 *
 * <p>这几条判定的价值全在阈值和边界上，所以单独测：
 * 它们要在<b>真正出问题时</b>响，而且<b>只在那时候</b>响。
 * 误报会让人学会忽略告警，那比不报还糟。
 */
class QueueHealthTest {

    private static final Duration MAX_HOLD = Duration.ofMinutes(15);

    private static QueueHealth healthy() {
        return new QueueHealth(0, 2, Duration.ofSeconds(3), 3, 1, 8, Duration.ofSeconds(20));
    }

    @Test
    @DisplayName("正常状态下不报任何东西 —— 误报会让人学会忽略告警")
    void 健康时安静() {
        assertThat(healthy().concerns(MAX_HOLD)).isEmpty();
    }

    @Test
    @DisplayName("PEL 最老 idle 越线 —— 这是 XAUTOCLAIM 停跑的唯一信号")
    void PEL滞留会报警() {
        QueueHealth stuck = new QueueHealth(
                0, 5, Duration.ofMinutes(6), 3, 0, 8, null);

        assertThat(stuck.concerns(MAX_HOLD))
                .hasSize(1)
                .element(0).asString()
                .contains("回收很可能没在跑")
                .contains("Redis 不会自动把 pending 放回队列");
    }

    @Test
    @DisplayName("恰好等于告警线时不报 —— 严格大于，边界有确定含义")
    void 边界不误报() {
        QueueHealth boundary = new QueueHealth(
                0, 5, QueueHealth.PEL_IDLE_ALARM, 3, 0, 8, null);

        assertThat(boundary.concerns(MAX_HOLD)).isEmpty();
    }

    @Test
    @DisplayName("持牌接近上限就报 —— 等它真触达时用户已经白等了一次")
    void 接近卡死时提前报警() {
        // 八成 = 12 分钟
        QueueHealth wedging = new QueueHealth(
                0, 1, Duration.ofSeconds(3), 3, 1, 8, Duration.ofMinutes(13));

        assertThat(wedging.concerns(MAX_HOLD))
                .hasSize(1)
                .element(0).asString()
                .contains("接近上限")
                .contains("趁现在看它卡在哪儿");
    }

    @Test
    @DisplayName("长但没到八成的 turn 不报 —— 慢不等于卡死")
    void 慢turn不误报() {
        QueueHealth slow = new QueueHealth(
                0, 1, Duration.ofSeconds(3), 3, 1, 8, Duration.ofMinutes(10));

        assertThat(slow.concerns(MAX_HOLD)).isEmpty();
    }

    @Test
    @DisplayName("槽位满载要报 —— 此时不再认领，积压会留在 ready 里")
    void 满载会报警() {
        QueueHealth saturated = new QueueHealth(
                120, 8, Duration.ofSeconds(3), 3, 8, 8, Duration.ofSeconds(30));

        assertThat(saturated.concerns(MAX_HOLD))
                .hasSize(1)
                .element(0).asString().contains("槽位已满（8/8）");
    }

    @Test
    @DisplayName("多项同时异常时逐条报出 —— 压成一个 boolean 就没法知道该查哪边")
    void 多项异常各报各的() {
        QueueHealth bad = new QueueHealth(
                500, 99, Duration.ofMinutes(30), 5, 8, 8, Duration.ofMinutes(14));

        assertThat(bad.concerns(MAX_HOLD)).hasSize(3);
    }

    @Test
    @DisplayName("没有在飞 turn 时不拿 null 去比 —— 空闲的 pod 不该因为没数据而报警")
    void 空闲时不报警() {
        QueueHealth idle = new QueueHealth(0, 0, null, 3, 0, 8, null);

        assertThat(idle.concerns(MAX_HOLD)).isEmpty();
        assertThat(idle.summary()).contains("最老idle=-").contains("最长持牌=-");
    }

    @Test
    @DisplayName("摘要把全局与本机分开写 —— 混着看会得出错误结论")
    void 摘要区分全局与本机() {
        String summary = healthy().summary();

        assertThat(summary).contains("队列[").contains("本机[");
        assertThat(summary).contains("PEL=2").contains("在飞=1/8");
    }

    @Test
    @DisplayName("时长格式化便于扫读")
    void 时长可读() {
        assertThat(QueueHealth.format(null)).isEqualTo("-");
        assertThat(QueueHealth.format(Duration.ofSeconds(45))).isEqualTo("45s");
        assertThat(QueueHealth.format(Duration.ofMinutes(5))).isEqualTo("5m");
        assertThat(QueueHealth.format(Duration.ofSeconds(150))).isEqualTo("2m30s");
        assertThat(QueueHealth.format(Duration.ofMinutes(90))).isEqualTo("1h30m");
    }
}
