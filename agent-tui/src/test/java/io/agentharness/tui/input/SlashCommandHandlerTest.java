package io.agentharness.tui.input;

import io.agentharness.protocol.ControlFrame;
import io.agentharness.protocol.SessionRef;
import io.agentharness.protocol.TurnPhase;
import io.agentharness.tui.render.LineKind;
import io.agentharness.tui.render.RenderedLine;
import io.agentharness.tui.state.ConnectionState;
import io.agentharness.tui.state.UiState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlashCommandHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-24T10:00:00Z");

    private final SlashCommandHandler handler = new SlashCommandHandler(() -> "s-generated");

    private UiState idle() {
        return UiState.initial(SessionRef.of("dev", "s-local"), "loopback")
                .withConnection(ConnectionState.CONNECTED);
    }

    private UiState running() {
        return idle().withControl(
                ControlFrame.idle().withTurnStarted("r-1").withPhase(TurnPhase.WRITING), NOW);
    }

    private CommandOutcome run(SlashCommand command, String argument, UiState state) {
        return handler.handle(new InputAction.RunCommand(command, argument), state, NOW);
    }

    private List<String> textOf(CommandOutcome outcome) {
        return ((CommandOutcome.Print) outcome).lines().stream().map(RenderedLine::text).toList();
    }

    @Nested
    @DisplayName("/new")
    class New {

        @Test
        void 不带参数时用生成的会话id() {
            CommandOutcome outcome = run(SlashCommand.NEW, "", idle());

            assertThat(outcome).isEqualTo(new CommandOutcome.SwitchSession("s-generated"));
        }

        @Test
        void 带参数时用指定的会话id() {
            assertThat(run(SlashCommand.NEW, "s-42", idle()))
                    .isEqualTo(new CommandOutcome.SwitchSession("s-42"));
        }

        @Test
        void 参数首尾空白被裁掉() {
            assertThat(run(SlashCommand.NEW, "  s-42  ", idle()))
                    .isEqualTo(new CommandOutcome.SwitchSession("s-42"));
        }

        @Test
        @DisplayName("turn 进行中拒绝切换 —— 切走之后那一轮的输出再也看不到")
        void 回复进行中不允许切走() {
            CommandOutcome outcome = run(SlashCommand.NEW, "", running());

            assertThat(outcome).isInstanceOf(CommandOutcome.Print.class);
            assertThat(textOf(outcome)).singleElement()
                    .satisfies(text -> assertThat(text).contains("先 /stop"));
        }

        @Test
        @DisplayName("切到当前会话是空操作 —— 照常执行会重拉一遍历史，屏幕上多出重复的对话")
        void 切到当前会话被拦下() {
            CommandOutcome outcome = run(SlashCommand.NEW, "s-local", idle());

            assertThat(textOf(outcome)).singleElement()
                    .satisfies(text -> assertThat(text).contains("已经在会话 s-local"));
        }

        @Test
        @DisplayName("非法会话 id 当场拒绝 —— 不挡的话要到投递那一刻才失败")
        void 含结构字符的会话id被拒绝() {
            CommandOutcome outcome = run(SlashCommand.NEW, "a:b", idle());

            assertThat(outcome).isInstanceOf(CommandOutcome.Print.class);
            assertThat(((CommandOutcome.Print) outcome).lines().get(0).kind())
                    .isEqualTo(LineKind.ERROR);
            assertThat(textOf(outcome)).anySatisfy(text -> assertThat(text).contains("非法字符"));
            assertThat(textOf(outcome)).anySatisfy(text -> assertThat(text).contains("/new"));
        }
    }

    @Nested
    @DisplayName("/stop")
    class Stop {

        @Test
        void 回复进行中发出中断() {
            assertThat(run(SlashCommand.STOP, "", running()))
                    .isEqualTo(new CommandOutcome.Interrupt());
        }

        @Test
        @DisplayName("空闲时不发指令 —— 目标 turn 不存在的停止会被服务端丢弃，白跑一趟")
        void 空闲时只给提示() {
            CommandOutcome outcome = run(SlashCommand.STOP, "", idle());

            assertThat(outcome).isInstanceOf(CommandOutcome.Print.class);
            assertThat(textOf(outcome)).singleElement()
                    .satisfies(text -> assertThat(text).contains("当前空闲"));
        }

        @Test
        void turn标记为活跃但没有replyId时也不发() {
            // 这是个不该出现的中间态；发一条 targetReplyId 为 null 的指令只会在协议层炸掉
            UiState broken = idle().withControl(
                    new ControlFrame(true, false, null, TurnPhase.THINKING, false, null, "1"), NOW);

            assertThat(run(SlashCommand.STOP, "", broken)).isInstanceOf(CommandOutcome.Print.class);
        }
    }

    @Nested
    @DisplayName("/status")
    class Status {

        @Test
        void 展示会话与连接状态() {
            List<String> lines = textOf(run(SlashCommand.STATUS, "", idle()));

            assertThat(lines).anySatisfy(t -> assertThat(t).contains("用户").contains("dev"));
            assertThat(lines).anySatisfy(t -> assertThat(t).contains("会话").contains("s-local"));
            assertThat(lines).anySatisfy(t -> assertThat(t).contains("后端").contains("loopback"));
            assertThat(lines).anySatisfy(t -> assertThat(t).contains("已连接"));
        }

        @Test
        void 展示排查重连问题需要的水位与空窗() {
            UiState state = idle().withMsgSeq(42).withGapDetected();

            List<String> lines = textOf(run(SlashCommand.STATUS, "", state));

            assertThat(lines).anySatisfy(t -> assertThat(t).contains("本地最大 seq").contains("42"));
            assertThat(lines).anySatisfy(t -> assertThat(t).contains("空窗次数").contains("1"));
        }

        @Test
        @DisplayName("有在途输入时展示原文 —— 用户不本地回显，这是唯一能看到它的地方")
        void 展示投递中的原文() {
            List<String> lines = textOf(
                    run(SlashCommand.STATUS, "", idle().withPendingInput("帮我订酒店")));

            assertThat(lines).anySatisfy(t -> assertThat(t).contains("投递中").contains("帮我订酒店"));
        }

        @Test
        void 空闲时不展示投递中() {
            assertThat(textOf(run(SlashCommand.STATUS, "", idle())))
                    .noneSatisfy(t -> assertThat(t).contains("投递中"));
        }
    }

    @Nested
    @DisplayName("其余命令")
    class Others {

        @Test
        void help列出全部命令() {
            List<String> lines = textOf(run(SlashCommand.HELP, "", idle()));

            for (SlashCommand command : SlashCommand.values()) {
                assertThat(lines).anySatisfy(t ->
                        assertThat(t).contains("/" + command.commandName()));
            }
        }

        @Test
        void clear与quit() {
            assertThat(run(SlashCommand.CLEAR, "", idle())).isEqualTo(new CommandOutcome.ClearScreen());
            assertThat(run(SlashCommand.QUIT, "", idle())).isEqualTo(new CommandOutcome.Quit());
        }

        @Test
        void session不带参数时给用法() {
            assertThat(textOf(run(SlashCommand.SESSION, "", idle()))).singleElement()
                    .satisfies(text -> assertThat(text).contains("用法：/session"));
        }

        @Test
        void session带参数时切换() {
            assertThat(run(SlashCommand.SESSION, "s-42", idle()))
                    .isEqualTo(new CommandOutcome.SwitchSession("s-42"));
        }

        @Test
        void session的错误提示指向session而不是new() {
            assertThat(textOf(run(SlashCommand.SESSION, "a:b", idle())))
                    .anySatisfy(text -> assertThat(text).contains("/session"));
        }
    }
}
