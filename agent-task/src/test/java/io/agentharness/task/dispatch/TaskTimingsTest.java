package io.agentharness.task.dispatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 覆盖 Test/P3 的 RCV-003：**MIN-IDLE-TIME 固定 60000，且 0 / 负数 / 过小的配置在启动时拒绝。**
 *
 * <p>这些断言看着像在测构造器，其实在测一条不变量：这几个时间参数之间的比例关系
 * 一旦被破坏，故障是静默的 —— 健康 pod 的令牌被抢走，表现为莫名其妙的双跑。
 */
class TaskTimingsTest {

    @Test
    @DisplayName("RCV-003 生产值就是规划冻结的那一组")
    void 生产值符合冻结契约() {
        TaskTimings timings = TaskTimings.production();

        assertThat(timings.reclaimMinIdle()).isEqualTo(Duration.ofMillis(60_000));
        assertThat(timings.reclaimInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(timings.leaseTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(timings.renewInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(timings.consumerIdleThreshold()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("RCV-003 MIN-IDLE 为 0 时启动失败 —— 0 会抢走存活 pod 的令牌")
    void 回收门槛不能为零() {
        assertThatThrownBy(() -> new TaskTimings(
                Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ZERO,
                Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofMillis(50),
                Duration.ofSeconds(20), Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reclaimMinIdle");
    }

    @Test
    @DisplayName("RCV-003 MIN-IDLE 为负数时启动失败")
    void 回收门槛不能为负() {
        assertThatThrownBy(() -> new TaskTimings(
                Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(-1),
                Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofMillis(50),
                Duration.ofSeconds(20), Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("RCV-003 MIN-IDLE 小于两个心跳周期时启动失败 —— 健康 pod 会被误判死亡")
    void 回收门槛必须留出心跳余量() {
        // 15s 的门槛配 10s 的心跳：只要一次心跳稍微晚一点，令牌就被别人抢走了
        assertThatThrownBy(() -> new TaskTimings(
                Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(15),
                Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofMillis(50),
                Duration.ofSeconds(20), Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("两次心跳之间");
    }

    @Test
    @DisplayName("续租周期不短于 lease TTL 时启动失败 —— 牌子会在续租前就过期")
    void 续租必须快于过期() {
        assertThatThrownBy(() -> new TaskTimings(
                Duration.ofSeconds(10), Duration.ofSeconds(10),
                Duration.ofSeconds(60), Duration.ofSeconds(30),
                Duration.ofHours(1), Duration.ofMillis(50), Duration.ofSeconds(20),
                Duration.ofMinutes(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("续租周期");
    }

    @Test
    @DisplayName("测试值等比缩放，比例关系与生产一致 —— 否则测试绿着生产坏着")
    void 测试值保持比例() {
        TaskTimings scaled = TaskTimings.scaledForTests(100);

        assertThat(scaled.reclaimMinIdle()).isEqualTo(Duration.ofMillis(600));
        assertThat(scaled.renewInterval()).isEqualTo(Duration.ofMillis(100));
        // 关键：缩放后仍然满足"门槛 ≥ 两个心跳周期"，构造器的校验照跑
        assertThat(scaled.reclaimMinIdle())
                .isGreaterThanOrEqualTo(scaled.renewInterval().multipliedBy(2));
    }

    @Test
    @DisplayName("CHA-011 硬杀恢复的时延上界是 MIN-IDLE + 回收周期，不是 lease TTL")
    void 恢复时延上界不等于租约时长() {
        TaskTimings timings = TaskTimings.production();

        // 这条断言存在的意义是把规划里的矛盾钉在测试上：
        // 规划 E 节 P3 验收写"恢复时延 ≈ lease TTL（30s）"，
        // 而冻结的参数算出来是 90s。数字对不上时应当改规划或改参数，不是改这条断言
        assertThat(timings.worstCaseTakeoverDelay()).isEqualTo(Duration.ofSeconds(90));
        assertThat(timings.worstCaseTakeoverDelay()).isGreaterThan(timings.leaseTtl());
    }
}
