package io.agentharness.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ControlFrameTest {

    @Test
    void 状态迁移全部返回新对象_原帧不变() {
        ControlFrame idle = ControlFrame.idle();
        ControlFrame running = idle.withTurnStarted("r1");

        assertThat(idle.turnActive()).isFalse();
        assertThat(idle.inputAllowed()).isTrue();
        assertThat(running).isNotSameAs(idle);
        assertThat(running.turnActive()).isTrue();
        assertThat(running.inputAllowed()).isFalse();
        assertThat(running.activeReplyId()).isEqualTo("r1");
    }

    @Test
    void 停止中仍持有turn_但不再允许输入() {
        ControlFrame stopping = ControlFrame.idle().withTurnStarted("r1").withStopping();

        assertThat(stopping.turnActive()).isTrue();
        assertThat(stopping.stopping()).isTrue();
        assertThat(stopping.inputAllowed()).isFalse();
        assertThat(stopping.phase()).isEqualTo(TurnPhase.STOPPING);
    }

    @Test
    void turn结束后释放输入权() {
        ControlFrame done = ControlFrame.idle().withTurnStarted("r1").withTurnEnded(TurnPhase.DONE);

        assertThat(done.turnActive()).isFalse();
        assertThat(done.inputAllowed()).isTrue();
        assertThat(done.activeReplyId()).isNull();
        assertThat(done.phase().isTerminal()).isTrue();
    }
}
