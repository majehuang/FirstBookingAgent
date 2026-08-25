package io.agentharness.tui;

import io.agentharness.protocol.SessionRef;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * TUI 启动参数。
 *
 * <p>{@code notes} 是欢迎语下面那几行提示，由装配方决定内容 ——
 * TUI 自己不知道进程里还嵌着什么（比如内嵌 worker），
 * 而"这个进程同时也在跑推理"恰恰是用户需要一眼看到的事。
 */
public record TuiConfig(
        SessionRef session,
        Path historyFile,
        boolean forcePlain,
        Duration statusTick,
        List<String> notes) {

    private static final Duration DEFAULT_TICK = Duration.ofMillis(200);

    public TuiConfig {
        Objects.requireNonNull(session, "session");
        statusTick = statusTick == null ? DEFAULT_TICK : statusTick;
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public static TuiConfig of(SessionRef session) {
        return new TuiConfig(session, defaultHistoryFile(), false, DEFAULT_TICK, List.of());
    }

    public TuiConfig withSession(SessionRef next) {
        return new TuiConfig(next, historyFile, forcePlain, statusTick, notes);
    }

    public TuiConfig withForcePlain(boolean plain) {
        return new TuiConfig(session, historyFile, plain, statusTick, notes);
    }

    public TuiConfig withNotes(List<String> lines) {
        return new TuiConfig(session, historyFile, forcePlain, statusTick, lines);
    }

    public static Path defaultHistoryFile() {
        String home = System.getProperty("user.home");
        return home == null ? null : Path.of(home, ".agent_tui_history");
    }
}
