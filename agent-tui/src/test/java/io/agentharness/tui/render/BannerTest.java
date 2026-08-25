package io.agentharness.tui.render;

import io.agentharness.protocol.ControlFrame;
import io.agentharness.protocol.SessionRef;
import io.agentharness.protocol.TurnPhase;
import io.agentharness.tui.input.SlashCommand;
import io.agentharness.tui.state.ConnectionState;
import io.agentharness.tui.state.UiState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BannerTest {

    private static final Instant NOW = Instant.parse("2026-08-23T10:00:00Z");
    private static final SessionRef SESSION = SessionRef.of("u1", "s-local");

    @Test
    void 欢迎语带会话标识与关键键位() {
        List<RenderedLine> lines = Banner.welcome(SESSION, "loopback");

        assertThat(lines).extracting(RenderedLine::text)
                .anySatisfy(text -> assertThat(text).contains("loopback"))
                .anySatisfy(text -> assertThat(text).contains("u1/s-local").contains("^C").contains("^D"));
    }

    @Test
    void help列出全部命令_新增命令不会漏掉() {
        List<RenderedLine> lines = Banner.help();

        for (SlashCommand command : SlashCommand.values()) {
            assertThat(lines).extracting(RenderedLine::text)
                    .anySatisfy(text -> assertThat(text).contains("/" + command.commandName()));
        }
    }

    @Test
    void help展示别名() {
        assertThat(Banner.help()).extracting(RenderedLine::text)
                .anySatisfy(text -> assertThat(text).contains("/quit").contains("/exit"));
    }

    @Test
    void status展示空窗次数与ctrl水位_这两项是排查重连问题的入口() {
        UiState state = UiState.initial(SESSION, "loopback")
                .withConnection(ConnectionState.CONNECTED)
                .withMsgSeq(42)
                .withGapDetected()
                .withControl(ControlFrame.idle().withTurnStarted("r-7").withCtrlId("1724-3"), NOW);

        List<String> texts = Banner.status(state, NOW.plusSeconds(2))
                .stream().map(RenderedLine::text).toList();

        assertThat(texts).anySatisfy(t -> assertThat(t).contains("本地最大 seq").contains("42"));
        assertThat(texts).anySatisfy(t -> assertThat(t).contains("空窗次数").contains("1"));
        assertThat(texts).anySatisfy(t -> assertThat(t).contains("ctrl 水位").contains("1724-3"));
        assertThat(texts).anySatisfy(t -> assertThat(t).contains("当前 replyId").contains("r-7"));
        assertThat(texts).anySatisfy(t -> assertThat(t).contains("已耗时").contains("2.0s"));
    }

    @Test
    void 空闲时不展示耗时行() {
        UiState idle = UiState.initial(SESSION, "loopback").withConnection(ConnectionState.CONNECTED);

        assertThat(Banner.status(idle, NOW)).extracting(RenderedLine::text)
                .noneSatisfy(text -> assertThat(text).contains("已耗时"));
    }

    @Test
    void 未开始过turn时replyId与水位显示占位符() {
        UiState idle = UiState.initial(SESSION, "loopback");

        assertThat(Banner.status(idle, NOW)).extracting(RenderedLine::text)
                .anySatisfy(text -> assertThat(text).contains("当前 replyId").contains("—"))
                .anySatisfy(text -> assertThat(text).contains("ctrl 水位").contains("—"));
    }

    @Test
    void 终态阶段在状态里如实展示() {
        UiState failed = UiState.initial(SESSION, "loopback")
                .withConnection(ConnectionState.CONNECTED)
                .withControl(ControlFrame.idle().withTurnEnded(TurnPhase.FAILED), NOW);

        assertThat(Banner.status(failed, NOW)).extracting(RenderedLine::text)
                .anySatisfy(text -> assertThat(text).contains("阶段").contains("已失败"));
    }
}
