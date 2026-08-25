package io.agentharness.tui.state;

import io.agentharness.protocol.ControlFrame;
import io.agentharness.protocol.SessionRef;
import io.agentharness.protocol.TurnPhase;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * TUI 的全部可见状态。不可变 —— 每次变更都产生新实例，由 {@link UiStateReducer} 统一驱动。
 *
 * <p>这样做的直接好处是状态行的渲染是一个纯函数：给定同一个 UiState 永远渲染出同一行文本，
 * 于是可以对着断言写测试，而不是靠盯屏幕。
 */
public record UiState(
        SessionRef session,
        String backendName,
        ConnectionState connection,
        ControlFrame control,
        long lastMsgSeq,
        long gapCount,
        Instant turnStartedAt,
        String pendingInput) {

    public UiState {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(backendName, "backendName");
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(control, "control");
    }

    public static UiState initial(SessionRef session, String backendName) {
        return new UiState(session, backendName, ConnectionState.CONNECTING,
                ControlFrame.idle(), 0L, 0L, null, null);
    }

    public UiState withConnection(ConnectionState next) {
        return new UiState(session, backendName, next, control, lastMsgSeq, gapCount,
                turnStartedAt, pendingInput);
    }

    /**
     * 记下一条已投递、但还没从流里回来的用户输入。
     *
     * <p>客户端不再本地回显，用户按下回车之后自己的话要等服务端落库并推回来才出现。
     * 这中间必须有反馈，否则看起来像卡住了 —— 状态行靠这个字段显示"投递中"。
     */
    public UiState withPendingInput(String text) {
        return new UiState(session, backendName, connection, control, lastMsgSeq, gapCount,
                turnStartedAt, text);
    }

    public boolean hasPendingInput() {
        return pendingInput != null && !pendingInput.isBlank();
    }

    public UiState withControl(ControlFrame next) {
        Instant startedAt = next.turnActive() && turnStartedAt == null ? Instant.now()
                : next.turnActive() ? turnStartedAt
                : null;
        return new UiState(session, backendName, connection, next, lastMsgSeq, gapCount,
                startedAt, pendingInput);
    }

    public UiState withControl(ControlFrame next, Instant now) {
        Instant startedAt = next.turnActive() && turnStartedAt == null ? now
                : next.turnActive() ? turnStartedAt
                : null;
        return new UiState(session, backendName, connection, next, lastMsgSeq, gapCount,
                startedAt, pendingInput);
    }

    public UiState withMsgSeq(long seq) {
        return new UiState(session, backendName, connection, control, seq, gapCount,
                turnStartedAt, pendingInput);
    }

    public UiState withGapDetected() {
        return new UiState(session, backendName, connection, control, lastMsgSeq, gapCount + 1,
                turnStartedAt, pendingInput);
    }

    public boolean inputAllowed() {
        return connection.canSend() && control.inputAllowed();
    }

    public TurnPhase phase() {
        return control.phase();
    }

    public Duration turnElapsed(Instant now) {
        return turnStartedAt == null ? Duration.ZERO : Duration.between(turnStartedAt, now);
    }
}
