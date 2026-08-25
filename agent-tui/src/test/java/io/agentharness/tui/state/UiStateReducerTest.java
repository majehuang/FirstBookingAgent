package io.agentharness.tui.state;

import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.ControlFrame;
import io.agentharness.protocol.SessionRef;
import io.agentharness.protocol.TurnPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UiStateReducerTest {

    private static final Instant AT = Instant.parse("2026-08-23T10:00:00Z");
    private static final SessionRef SESSION = SessionRef.of("u1", "s1");

    private UiState initial() {
        return UiState.initial(SESSION, "loopback");
    }

    @Test
    void 顺序到达的消息推进水位() {
        UiState state = initial();
        state = UiStateReducer.reduce(state, arrived(1));
        state = UiStateReducer.reduce(state, arrived(2));

        assertThat(state.lastMsgSeq()).isEqualTo(2);
        assertThat(state.gapCount()).isZero();
    }

    @Test
    void 重复消息不推进水位也不算空窗() {
        UiState state = UiStateReducer.reduce(initial(), arrived(1));
        UiState afterDuplicate = UiStateReducer.reduce(state, arrived(1));

        assertThat(afterDuplicate.lastMsgSeq()).isEqualTo(1);
        assertThat(afterDuplicate.gapCount()).isZero();
    }

    @Test
    void 跳号计一次空窗并把水位推到新值() {
        UiState state = UiStateReducer.reduce(initial(), arrived(1));
        UiState afterGap = UiStateReducer.reduce(state, arrived(9));

        assertThat(afterGap.gapCount()).isEqualTo(1);
        assertThat(afterGap.lastMsgSeq()).isEqualTo(9);
    }

    @Test
    void 归约永远返回新实例_原状态不被修改() {
        UiState before = initial();
        UiState after = UiStateReducer.reduce(before,
                new UiEvent.ConnectionChanged(ConnectionState.CONNECTED, AT));

        assertThat(before.connection()).isEqualTo(ConnectionState.CONNECTING);
        assertThat(after.connection()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(after).isNotSameAs(before);
    }

    @Test
    void turn开始时记录起始时刻_结束时清空() {
        UiState state = initial()
                .withConnection(ConnectionState.CONNECTED);
        state = UiStateReducer.reduce(state,
                new UiEvent.ControlArrived(ControlFrame.idle().withTurnStarted("r1"), AT));

        assertThat(state.turnStartedAt()).isEqualTo(AT);
        assertThat(state.turnElapsed(AT.plusSeconds(3)).toSeconds()).isEqualTo(3);
        assertThat(state.inputAllowed()).isFalse();

        state = UiStateReducer.reduce(state, new UiEvent.ControlArrived(
                state.control().withTurnEnded(TurnPhase.DONE), AT.plusSeconds(3)));

        assertThat(state.turnStartedAt()).isNull();
        assertThat(state.inputAllowed()).isTrue();
    }

    @Test
    @DisplayName("自己的话从流里回来了，投递中就结束了")
    void 用户消息到达时清掉pending() {
        UiState state = initial().withPendingInput("帮我订酒店");

        state = UiStateReducer.reduce(state, new UiEvent.MessageArrived(
                ClientMessage.userText(1, "r1", "u-i1", "帮我订酒店", AT), AT));

        assertThat(state.hasPendingInput()).isFalse();
        assertThat(state.lastMsgSeq()).isEqualTo(1);
    }

    @Test
    void 助手消息不清pending_自己的话还没回来() {
        UiState state = initial().withPendingInput("帮我订酒店");

        state = UiStateReducer.reduce(state, arrived(1));

        assertThat(state.hasPendingInput()).isTrue();
    }

    @Test
    void 重复的用户消息不清pending_它本来就没推进任何东西() {
        UiState state = UiStateReducer.reduce(initial(), new UiEvent.MessageArrived(
                ClientMessage.userText(5, "r1", "u-i1", "第一句", AT), AT));
        state = state.withPendingInput("第二句");

        // 重放窗口里的旧消息再次到达，判定为 DISCARD
        state = UiStateReducer.reduce(state, new UiEvent.MessageArrived(
                ClientMessage.userText(5, "r1", "u-i1", "第一句", AT), AT));

        assertThat(state.hasPendingInput()).isTrue();
    }

    @Test
    void Tick不改变任何状态() {
        UiState state = UiStateReducer.reduce(initial(), arrived(5));
        assertThat(UiStateReducer.reduce(state, new UiEvent.Tick(AT))).isEqualTo(state);
    }

    private UiEvent arrived(long seq) {
        ClientMessage message = ClientMessage.textDelta(seq, "r1", "b1", "x", AT);
        return new UiEvent.MessageArrived(message, AT);
    }
}
