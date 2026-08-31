package io.agentharness.task.dispatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 覆盖 Test/P3 的 GRP-004、GRP-005。 */
class ConsumerNameTest {

    @Test
    @DisplayName("GRP-004 空名、空白名、超长名启动失败并说清原因")
    void 非法名称启动即失败() {
        assertThatThrownBy(() -> ConsumerName.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> ConsumerName.of("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConsumerName.of("pod a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空白");
        assertThatThrownBy(() -> ConsumerName.of("x".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超长");
    }

    @Test
    @DisplayName("GRP-005 同一配置反复构造得到完全相同的名字 —— 不含时间戳或随机后缀")
    void 名字必须稳定() {
        String podName = "worker-7";

        ConsumerName first = ConsumerName.of(podName);
        ConsumerName second = ConsumerName.of(podName);

        assertThat(first).isEqualTo(second);
        assertThat(first.value()).isEqualTo(podName);
        // 反证：加了后缀的话，这条会因为两次构造不同而失败；
        // 而生产上的表现是每重启一次留下一个死 consumer，元数据无界增长
        assertThat(first.value()).doesNotContain("-" + ProcessHandle.current().pid());
    }

    @Test
    @DisplayName("合法名字原样保留，不做归一化")
    void 合法名字不被改写() {
        assertThat(ConsumerName.of("Pod_A.1").value()).isEqualTo("Pod_A.1");
        assertThat(ConsumerName.of("x".repeat(128)).value()).hasSize(128);
    }
}
