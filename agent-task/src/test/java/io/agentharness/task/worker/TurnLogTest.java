package io.agentharness.task.worker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TurnLogTest {

    private static final Instant AT = Instant.parse("2026-08-25T10:03:11Z");

    @Test
    @DisplayName("成功那行带上引擎名 —— 「到底接没接上模型」是看日志时最常问的一句")
    void 成功行带引擎名与计数() {
        String line = TurnLog.TurnSummary
                .done(AT, "s-1", "r-2cad459a", "kimi-for-coding", 34, 8, Duration.ofMillis(1400))
                .format();

        assertThat(line).contains("s-1").contains("r-2cad459a").contains("kimi-for-coding");
        assertThat(line).contains("✓ 完成").contains("事件 34").contains("消息 8").contains("1.4s");
    }

    @Test
    @DisplayName("失败行给原因，不是只给一个叉")
    void 失败行带原因() {
        String line = TurnLog.TurnSummary
                .failed(AT, "s-1", "r-8f1e0022", "kimi-for-coding", 2, 1,
                        Duration.ofSeconds(3), "连接超时")
                .format();

        assertThat(line).contains("✗ 失败").contains("连接超时").contains("3.0s");
    }

    @Test
    @DisplayName("原因缺失时也要有话说 —— 空字符串会打出一行看不出所以然的日志")
    void 失败原因缺失时兜底() {
        TurnLog.TurnSummary summary = TurnLog.TurnSummary
                .failed(AT, "s-1", "r-1", "scripted", 0, 0, Duration.ZERO, null);

        assertThat(summary.succeeded()).isFalse();
        assertThat(summary.format()).contains("未知原因");
    }

    @Test
    void 关闭时什么都不做() {
        TurnLog.disabled().turnFinished(TurnLog.TurnSummary
                .done(AT, "s-1", "r-1", "scripted", 1, 1, Duration.ZERO));
    }
}
