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
        return UiState.initial(SessionRef.of("u1", "s-local"), "redis")
                .withConnection(ConnectionState.CONNECTED);
    }

    @Test
    void 空闲状态展示会话_水位与后端() {
        String line = StatusLine.render(connected(), NOW, 120);

        assertThat(line).startsWith("⏵ 空闲  ·  s-local  ·  seq 0  ·  redis");
        assertThat(line).endsWith("^C 停止  ^D 退出  /help");
        // 断言的是终端列数，不是字符数 —— 中文一个字占两列
        assertThat(DisplayWidth.of(line)).isEqualTo(120);
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
        UiState state = UiState.initial(SessionRef.of("u1", "s-local"), "redis")
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
        assertThat(DisplayWidth.of(narrow)).isLessThanOrEqualTo(50);
    }

    @Test
    @DisplayName("窄终端整段丢弃，而不是把某一段截掉半截")
    void 极窄终端丢段而非截断() {
        String tiny = StatusLine.render(connected(), NOW, 12);

        // 放不下就整段不要。截成 "⏵ 空闲  ·  s…" 那样的半截 session id 没有任何用处
        assertThat(tiny).isEqualTo("⏵ 空闲");
        assertThat(DisplayWidth.of(tiny)).isLessThanOrEqualTo(12);
    }

    @Test
    @DisplayName("连头段都放不下时才截断，此时仍带省略号")
    void 头段放不下才截断() {
        UiState writing = connected()
                .withControl(ControlFrame.idle().withTurnStarted("r1").withPhase(TurnPhase.WRITING), NOW);

        String tiny = StatusLine.render(writing, NOW.plusSeconds(9), 6);

        // 下一个字是中文，占两列，放不进剩下的一列 —— 于是停在 5 列。
        // 宁可短一列也不能超：超了就折行，状态行会变成两行
        assertThat(DisplayWidth.of(tiny)).isLessThanOrEqualTo(6);
        assertThat(tiny).endsWith("…");
    }

    // ---------- 控制状态（P2-8） ----------

    @Test
    @DisplayName("展示 activeReplyId 与 ctrlId 水位 —— 控制通道出错只在这里看得见")
    void 展示活跃回复与控制水位() {
        UiState state = connected().withControl(
                ControlFrame.idle().withTurnStarted("r-abc123").withPhase(TurnPhase.WRITING)
                        .withCtrlId("1724502938471-0"), NOW);

        String line = StatusLine.render(state, NOW, 140);

        assertThat(line).contains("⌁ r-abc123");
        // 前缀是毫秒时间戳，同一会话内几乎不变，留尾部才分得出先后
        assertThat(line).contains("ctrl …938471-0");
    }

    @Test
    @DisplayName("/stop 发出后挂出「已发出」，直到控制帧认下它")
    void 在途控制指令可见() {
        UiState sent = connected()
                .withControl(ControlFrame.idle().withTurnStarted("r-1").withPhase(TurnPhase.WRITING), NOW)
                .withPendingControl("/stop");

        assertThat(StatusLine.render(sent, NOW, 140)).contains("⇱ /stop 已发出");
    }

    @Test
    @DisplayName("控制帧认下之后标记消失 —— 否则会一直显示成没生效")
    void 控制帧到达后清除在途标记() {
        UiState sent = connected()
                .withControl(ControlFrame.idle().withTurnStarted("r-1").withPhase(TurnPhase.WRITING), NOW)
                .withPendingControl("/stop");

        UiState acknowledged = sent.withControl(
                sent.control().withStopping().withCtrlId("1724502938999-0"), NOW);

        assertThat(acknowledged.hasPendingControl()).isFalse();
        assertThat(StatusLine.render(acknowledged, NOW, 140)).doesNotContain("已发出");
    }

    @Test
    @DisplayName("turn 自己跑完也算认下 —— 否则标记永远挂着")
    void turn结束同样清除在途标记() {
        UiState sent = connected()
                .withControl(ControlFrame.idle().withTurnStarted("r-1").withPhase(TurnPhase.WRITING), NOW)
                .withPendingControl("/stop");

        UiState ended = sent.withControl(ControlFrame.idle().withTurnEnded(TurnPhase.DONE), NOW);

        assertThat(ended.hasPendingControl()).isFalse();
    }

    @Test
    @DisplayName("没有 turn 却不让输入 —— 这是卡住了，必须看得见")
    void 输入被锁死时给出提示() {
        ControlFrame stuck = new ControlFrame(false, false, null, TurnPhase.IDLE, false, null, null);

        assertThat(StatusLine.render(connected().withControl(stuck, NOW), NOW, 140))
                .contains("⌾ 输入锁定");
    }

    @Test
    @DisplayName("turn 进行中输入本就该锁着，不重复提示")
    void turn进行中不提示输入锁定() {
        UiState running = connected().withControl(
                ControlFrame.idle().withTurnStarted("r-1").withPhase(TurnPhase.WRITING), NOW);

        assertThat(StatusLine.render(running, NOW, 140)).doesNotContain("输入锁定");
    }

    @Test
    @DisplayName("中文标签按列算宽 —— 按字符数算会撑破终端而折行")
    void 中文不撑破终端宽度() {
        UiState busy = connected()
                .withControl(ControlFrame.idle().withTurnStarted("r-2cad459a-441")
                        .withPhase(TurnPhase.WRITING).withCtrlId("1787627140230-0"), NOW)
                .withPendingControl("/stop")
                .withMsgSeq(42);

        for (int width = 20; width <= 130; width++) {
            assertThat(DisplayWidth.of(StatusLine.render(busy, NOW.plusSeconds(2), width)))
                    .as("宽度 %d", width)
                    .isLessThanOrEqualTo(width);
        }
    }

    @Test
    @DisplayName("宽度不够时先丢后端名，控制指令留到最后")
    void 按优先级丢弃而非按书写顺序() {
        UiState state = connected()
                .withControl(ControlFrame.idle().withTurnStarted("r-abcdef123456")
                        .withPhase(TurnPhase.WRITING).withCtrlId("1724502938471-0"), NOW)
                .withPendingControl("/stop")
                .withMsgSeq(42);

        String squeezed = StatusLine.render(state, NOW, 46);

        assertThat(DisplayWidth.of(squeezed)).isLessThanOrEqualTo(46);
        assertThat(squeezed).contains("⇱ /stop 已发出");
        assertThat(squeezed).doesNotContain("redis");
    }
}
