package io.agentharness.protocol;

/**
 * 控制状态快照。LWW 语义，但每次变更都会追加一帧到 ctrl-stream。
 *
 * <p>{@code ctrlId} 是流水位，必须与 XADD 在同一个 Lua 脚本内产生（INV-11）——
 * 否则快照与重放起点错开，会出现状态翻转。本轮 TUI 只消费它，产生方在 P5 交付。
 */
public record ControlFrame(
        boolean turnActive,
        boolean inputAllowed,
        String activeReplyId,
        TurnPhase phase,
        boolean stopping,
        String supersededReplyId,
        String ctrlId) {

    public ControlFrame {
        Validate.required(phase, "phase");
    }

    public static ControlFrame idle() {
        return new ControlFrame(false, true, null, TurnPhase.IDLE, false, null, null);
    }

    public ControlFrame withPhase(TurnPhase newPhase) {
        return new ControlFrame(turnActive, inputAllowed, activeReplyId, newPhase, stopping, supersededReplyId, ctrlId);
    }

    public ControlFrame withTurnStarted(String replyId) {
        return new ControlFrame(true, false, replyId, TurnPhase.QUEUED, false, supersededReplyId, ctrlId);
    }

    public ControlFrame withTurnEnded(TurnPhase finalPhase) {
        return new ControlFrame(false, true, null, finalPhase, false, supersededReplyId, ctrlId);
    }

    public ControlFrame withStopping() {
        return new ControlFrame(turnActive, false, activeReplyId, TurnPhase.STOPPING, true, supersededReplyId, ctrlId);
    }

    public ControlFrame withCtrlId(String newCtrlId) {
        return new ControlFrame(turnActive, inputAllowed, activeReplyId, phase, stopping, supersededReplyId, newCtrlId);
    }
}
