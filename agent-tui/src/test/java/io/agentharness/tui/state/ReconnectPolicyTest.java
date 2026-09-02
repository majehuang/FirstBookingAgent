package io.agentharness.tui.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ReconnectPolicyTest {

    @Test
    @DisplayName("指数退避：1s → 2s → 4s → 8s → 16s")
    void 指数退避() {
        assertThat(ReconnectPolicy.delayFor(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(ReconnectPolicy.delayFor(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(ReconnectPolicy.delayFor(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(ReconnectPolicy.delayFor(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(ReconnectPolicy.delayFor(5)).isEqualTo(Duration.ofSeconds(16));
    }

    @Test
    @DisplayName("30 秒封顶 —— 翻到分钟级之后用户看到的是「网络好了它还不连」")
    void 三十秒封顶() {
        assertThat(ReconnectPolicy.delayFor(6)).isEqualTo(Duration.ofSeconds(30));
        assertThat(ReconnectPolicy.delayFor(7)).isEqualTo(Duration.ofSeconds(30));
        assertThat(ReconnectPolicy.delayFor(100)).isEqualTo(Duration.ofSeconds(30));
        // 次数大到移位会溢出也不能绕回小值
        assertThat(ReconnectPolicy.delayFor(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("非法输入按第一次处理，不抛异常 —— 重连路径上没有可恢复的调用方")
    void 非法输入按第一次处理() {
        assertThat(ReconnectPolicy.delayFor(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(ReconnectPolicy.delayFor(-3)).isEqualTo(Duration.ofSeconds(1));
    }
}
