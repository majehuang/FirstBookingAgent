package io.agentharness.tui;

import io.agentharness.protocol.SessionRef;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** TUI 启动参数。 */
public record TuiConfig(
        SessionRef session,
        Path historyFile,
        boolean forcePlain,
        Duration statusTick) {

    private static final Duration DEFAULT_TICK = Duration.ofMillis(200);

    public TuiConfig {
        Objects.requireNonNull(session, "session");
        statusTick = statusTick == null ? DEFAULT_TICK : statusTick;
    }

    public static TuiConfig of(SessionRef session) {
        return new TuiConfig(session, defaultHistoryFile(), false, DEFAULT_TICK);
    }

    public TuiConfig withSession(SessionRef next) {
        return new TuiConfig(next, historyFile, forcePlain, statusTick);
    }

    public TuiConfig withForcePlain(boolean plain) {
        return new TuiConfig(session, historyFile, plain, statusTick);
    }

    public static Path defaultHistoryFile() {
        String home = System.getProperty("user.home");
        return home == null ? null : Path.of(home, ".agent_tui_history");
    }
}
