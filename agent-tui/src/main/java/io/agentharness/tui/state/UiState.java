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
        String pendingInput,
        String pendingControl) {

    public UiState {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(backendName, "backendName");
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(control, "control");
    }

    public static UiState initial(SessionRef session, String backendName) {
        return new UiState(session, backendName, ConnectionState.CONNECTING,
                ControlFrame.idle(), 0L, 0L, null, null, null);
    }

    public UiState withConnection(ConnectionState next) {
        return new UiState(session, backendName, next, control, lastMsgSeq, gapCount,
                turnStartedAt, pendingInput, pendingControl);
    }

    /**
     * 记下一条已投递、但还没从流里回来的用户输入。
     *
     * <p>客户端不再本地回显，用户按下回车之后自己的话要等服务端落库并推回来才出现。
     * 这中间必须有反馈，否则看起来像卡住了 —— 状态行靠这个字段显示"投递中"。
     */
    public UiState withPendingInput(String text) {
        return new UiState(session, backendName, connection, control, lastMsgSeq, gapCount,
                turnStartedAt, text, pendingControl);
    }

    /**
     * 记下一条已发出、服务端还没确认的控制指令（目前只有 /stop）。
     *
     * <p>控制指令与普通消息不同：它<b>不会</b>在流里以消息形式回来，
     * 唯一的回执是控制帧的变化。中间这段空窗如果没有反馈，
     * 用户会以为 /stop 没生效而反复按 —— 于是 inbox 里堆一串取消指令。
     */
    public UiState withPendingControl(String label) {
        return new UiState(session, backendName, connection, control, lastMsgSeq, gapCount,
                turnStartedAt, pendingInput, label);
    }

    public boolean hasPendingControl() {
        return pendingControl != null && !pendingControl.isBlank();
    }

    public boolean hasPendingInput() {
        return pendingInput != null && !pendingInput.isBlank();
    }

    public UiState withControl(ControlFrame next) {
        return withControl(next, Instant.now());
    }

    public UiState withControl(ControlFrame next, Instant now) {
        Instant startedAt = next.turnActive() && turnStartedAt == null ? now
                : next.turnActive() ? turnStartedAt
                : null;
        // 服务端认下了这条控制指令，"已发出"到此结束。
        // 判据是 stopping 置起或这一轮已经结束 —— 后者覆盖了
        // 停止指令到达时 turn 恰好自己跑完的情况，否则标记会一直挂着
        String stillPending = next.stopping() || !next.turnActive() ? null : pendingControl;
        return new UiState(session, backendName, connection, next, lastMsgSeq, gapCount,
                startedAt, pendingInput, stillPending);
    }

    public UiState withMsgSeq(long seq) {
        return new UiState(session, backendName, connection, control, seq, gapCount,
                turnStartedAt, pendingInput, pendingControl);
    }

    public UiState withGapDetected() {
        return new UiState(session, backendName, connection, control, lastMsgSeq, gapCount + 1,
                turnStartedAt, pendingInput, pendingControl);
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
