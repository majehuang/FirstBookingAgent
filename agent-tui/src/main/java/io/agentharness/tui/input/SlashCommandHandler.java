package io.agentharness.tui.input;

import io.agentharness.protocol.ProtocolException;
import io.agentharness.protocol.SessionRef;
import io.agentharness.tui.render.Banner;
import io.agentharness.tui.render.LineKind;
import io.agentharness.tui.render.RenderedLine;
import io.agentharness.tui.state.UiState;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 斜杠命令的处理逻辑。
 *
 * <p>纯函数：给定 {@link UiState} 与命令，返回要执行的 {@link CommandOutcome}，不碰终端也不碰网络。
 * 会话 id 的生成由外部注入，因此连 {@code /new} 这种带随机性的命令也能确定性地断言。
 */
public final class SlashCommandHandler {

    private final Supplier<String> sessionIdGenerator;

    public SlashCommandHandler(Supplier<String> sessionIdGenerator) {
        this.sessionIdGenerator = Objects.requireNonNull(sessionIdGenerator, "sessionIdGenerator");
    }

    public CommandOutcome handle(InputAction.RunCommand command, UiState state, Instant now) {
        return switch (command.command()) {
            case HELP -> new CommandOutcome.Print(Banner.help());
            case STATUS -> new CommandOutcome.Print(Banner.status(state, now));
            case STOP -> stop(state);
            case CLEAR -> new CommandOutcome.ClearScreen();
            case NEW -> switchTo(command.argument().isBlank()
                    ? sessionIdGenerator.get() : command.argument().strip(), state, true);
            case SESSION -> command.argument().isBlank()
                    ? CommandOutcome.Print.of(RenderedLine.hint("用法：/session <sessionId>"))
                    : switchTo(command.argument().strip(), state, false);
            case TRACE -> trace(command.argument());
            case DOCTOR -> new CommandOutcome.Diagnose(CommandOutcome.Diagnose.Kind.DOCTOR);
            case KEYS -> new CommandOutcome.Diagnose(CommandOutcome.Diagnose.Kind.KEYS);
            case QUIT -> new CommandOutcome.Quit();
        };
    }

    /**
     * {@code /trace [on|off]}。
     *
     * <p>不带参数<b>不做切换而是报状态</b>。切换看起来更省事，但追踪是个不可见的开关 ——
     * 敲第二次以为是重复确认、实际上把它关掉了，而现场表现是"追踪时有时无"。
     */
    private CommandOutcome trace(String argument) {
        String value = argument.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "" -> new CommandOutcome.Trace(CommandOutcome.Trace.Action.SHOW);
            case "on", "开" -> new CommandOutcome.Trace(CommandOutcome.Trace.Action.ON);
            case "off", "关" -> new CommandOutcome.Trace(CommandOutcome.Trace.Action.OFF);
            default -> CommandOutcome.Print.of(
                    RenderedLine.hint("用法：/trace [on|off]，不带参数则报当前状态"));
        };
    }

    /**
     * {@code /stop} 与 Ctrl+C 等价。
     *
     * <p>空闲时不发指令：目标 turn 不存在的停止指令会被服务端丢弃，
     * 白跑一趟不如直接告诉用户当前没有可停的东西。
     */
    private CommandOutcome stop(UiState state) {
        if (!state.control().turnActive() || state.control().activeReplyId() == null) {
            return CommandOutcome.Print.of(RenderedLine.hint("当前空闲，没有正在进行的回复"));
        }
        return new CommandOutcome.Interrupt();
    }

    /**
     * 切换会话。
     *
     * <p>两处刻意的拦截：
     * <ul>
     *   <li><b>turn 进行中不允许切走。</b>切走之后那一轮仍在服务端跑，
     *       但用户再也看不到它的输出 —— 表现是"回复凭空消失了"。先 {@code /stop}。</li>
     *   <li><b>切到当前会话是空操作。</b>照常执行会重建两条流、重拉一遍历史，
     *       屏幕上凭空多出一份重复的对话。</li>
     * </ul>
     */
    private CommandOutcome switchTo(String sessionId, UiState state, boolean isNew) {
        if (state.control().turnActive()) {
            return CommandOutcome.Print.of(RenderedLine.hint(
                    "回复进行中，切换会话会让这一轮的输出再也看不到。先 /stop 或等它结束。"));
        }
        if (sessionId.equals(state.session().sessionId())) {
            return CommandOutcome.Print.of(RenderedLine.hint(
                    "已经在会话 " + sessionId + " 里了"));
        }
        try {
            // 借协议层的校验挡住非法 id：不挡的话会一路创建到投递那一刻才失败，
            // 而那时的错误信息指向 Redis，不指向当初这个 id
            SessionRef.of(state.session().userId(), sessionId);
        } catch (ProtocolException e) {
            return CommandOutcome.Print.of(
                    RenderedLine.of(LineKind.ERROR, "✗ " + e.getMessage()),
                    RenderedLine.hint(isNew ? "用法：/new [sessionId]" : "用法：/session <sessionId>"));
        }
        return new CommandOutcome.SwitchSession(sessionId);
    }

    /** 默认的会话 id 生成方式。 */
    public static Supplier<String> randomSessionIds() {
        return () -> "s-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
