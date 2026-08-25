package io.agentharness.tui.state;

import java.util.Objects;

/**
 * 状态归约：{@code (state, event) -> state}，纯函数，无 IO、无副作用。
 *
 * <p>消息的序号判定在这里落地。判定为 GAP 时状态里的 {@code gapCount} 自增，
 * 由调用方据此触发"清空缓冲 → 拉取历史 → 重建"，reducer 本身不发起任何拉取。
 */
public final class UiStateReducer {

    private UiStateReducer() {
    }

    public static UiState reduce(UiState state, UiEvent event) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(event, "event");

        return switch (event) {
            case UiEvent.MessageArrived e -> onMessage(state, e);
            case UiEvent.ControlArrived e -> state.withControl(e.frame(), e.at());
            case UiEvent.ConnectionChanged e -> state.withConnection(e.connection());
            case UiEvent.Tick ignored -> state;
        };
    }

    private static UiState onMessage(UiState state, UiEvent.MessageArrived event) {
        long incoming = event.message().msgSeq();
        UiState advanced = switch (SeqRule.judge(state.lastMsgSeq(), incoming)) {
            case DISCARD -> state;
            case APPEND -> state.withMsgSeq(incoming);
            case GAP -> state.withGapDetected().withMsgSeq(incoming);
        };

        // 自己发的话从流里回来了，"投递中"到此结束
        if (advanced != state && event.message().fromUser()) {
            return advanced.withPendingInput(null);
        }
        return advanced;
    }

    /** 消息是否应当被渲染。DISCARD 的消息不进渲染管道。 */
    public static boolean shouldRender(UiState state, long incomingSeq) {
        return SeqRule.judge(state.lastMsgSeq(), incomingSeq) != SeqVerdict.DISCARD;
    }
}
