package io.agentharness.tui.render;

import io.agentharness.protocol.ControlFrame;
import io.agentharness.protocol.SessionRef;
import io.agentharness.protocol.TurnPhase;
import io.agentharness.tui.state.ConnectionState;
import io.agentharness.tui.state.UiState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StatusLineTest {

    private static final Instant NOW = Instant.parse("2026-08-23T10:00:00Z");

    private UiState connected() {
        return UiState.initial(SessionRef.of("u1", "s-local"), "loopback")
                .withConnection(ConnectionState.CONNECTED);
    }

    @Test
    void 空闲状态展示会话_水位与后端() {
        String line = StatusLine.render(connected(), NOW, 120);

        assertThat(line).startsWith("⏵ 空闲  ·  s-local  ·  seq 0  ·  loopback");
        assertThat(line).endsWith("^C 停止  ^D 退出  /help");
        assertThat(line).hasSize(120);
    }

    @Test
    void turn进行中展示阶段图标与耗时() {
        UiState state = connected()
                .withControl(ControlFrame.idle().withTurnStarted("r1").withPhase(TurnPhase.WRITING), NOW)
                .withMsgSeq(42);

        String line = StatusLine.render(state, NOW.plusMillis(1400), 120);

        assertThat(line).startsWith("✎ 生成中 1.4s");
        assertThat(line).contains("seq 42");
    }

    @Test
    void 超过一分钟的turn用分秒展示() {
        UiState state = connected()
                .withControl(ControlFrame.idle().withTurnStarted("r1").withPhase(TurnPhase.THINKING), NOW);

        assertThat(StatusLine.render(state, NOW.plusSeconds(95), 120)).startsWith("◐ 思考中 1:35");
    }

    @Test
    void 未连接时优先展示连接状态而不是turn阶段() {
        UiState state = UiState.initial(SessionRef.of("u1", "s-local"), "loopback")
                .withConnection(ConnectionState.DISCONNECTED);

        assertThat(StatusLine.render(state, NOW, 120)).startsWith("⏵ 已断开");
    }

    @Test
    void 空窗发生过时在状态行里留痕() {
        UiState state = connected().withGapDetected();

        assertThat(StatusLine.render(state, NOW, 120)).contains("空窗 1");
    }

    @Test
    @DisplayName("投递中优先展示 —— 用户不再本地回显，这段空窗必须有反馈")
    void 投递中展示原文预览() {
        UiState state = connected().withPendingInput("帮我订一间明天北京的酒店");

        String line = StatusLine.render(state, NOW, 120);

        assertThat(line).startsWith("⋯ 投递中 「帮我订一间明天北京的酒店」");
        assertThat(line).contains("s-local");
    }

    @Test
    void 过长的输入在预览里截断() {
        assertThat(StatusLine.preview("一".repeat(50))).hasSize(19).endsWith("…」");
        assertThat(StatusLine.preview("  多  空白\n换行  ")).isEqualTo("「多 空白 换行」");
    }

    @Test
    void 投递中压过turn阶段_因为用户此刻最关心自己那句话到没到() {
        UiState state = connected()
                .withControl(ControlFrame.idle().withTurnStarted("r1").withPhase(TurnPhase.WRITING), NOW)
                .withPendingInput("下一句");

        assertThat(StatusLine.render(state, NOW, 120)).startsWith("⋯ 投递中 「下一句」");
    }

    @Test
    void 窄终端先丢键位提示_再截断左侧() {
        String narrow = StatusLine.render(connected(), NOW, 50);

        assertThat(narrow).doesNotContain("^C");
        assertThat(narrow).hasSizeLessThanOrEqualTo(50);
    }

    @Test
    void 极窄终端截断后仍带省略号() {
        String tiny = StatusLine.render(connected(), NOW, 12);

        assertThat(tiny).hasSize(12).endsWith("…");
    }
}
