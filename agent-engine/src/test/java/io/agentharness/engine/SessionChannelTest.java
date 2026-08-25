package io.agentharness.engine;

import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.ControlFrame;
import io.agentharness.protocol.MessageRole;
import io.agentharness.protocol.MessageType;
import io.agentharness.protocol.TurnPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionChannelTest {

    private static final Instant AT = Instant.parse("2026-08-23T10:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    @DisplayName("新订阅者先拿到快照 —— 对应控制流建连的第一步")
    void 控制流对新订阅者重放最新帧() {
        SessionChannel channel = new SessionChannel();

        ControlFrame first = channel.control().blockFirst(TIMEOUT);

        assertThat(first).isNotNull();
        assertThat(first.turnActive()).isFalse();
        assertThat(first.phase()).isEqualTo(TurnPhase.IDLE);
    }

    @Test
    void 每次变更都推进ctrl水位() {
        SessionChannel channel = new SessionChannel();
        String before = channel.snapshot().ctrlId();

        channel.publishControl(channel.snapshot().withTurnStarted("r-1"));
        String after = channel.snapshot().ctrlId();

        assertThat(Long.parseLong(after)).isGreaterThan(before == null ? -1 : Long.parseLong(before));
        // replay-latest：变更之后新订阅者拿到的第一帧就是最新帧
        assertThat(channel.control().blockFirst(TIMEOUT).activeReplyId()).isEqualTo("r-1");
    }

    @Test
    void 消息流对新订阅者重放窗口内全部消息() {
        SessionChannel channel = new SessionChannel();
        channel.emit(message(1, MessageType.TEXT_DELTA, "你"));
        channel.emit(message(2, MessageType.TEXT_DELTA, "好"));

        List<ClientMessage> replayed = channel.messages().take(Duration.ofMillis(80))
                .collectList().block(TIMEOUT);

        assertThat(replayed).extracting(ClientMessage::msgSeq).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("工具调用优先于生成中 —— 它更能说明此刻在等什么")
    void 阶段推进以工具调用为先() {
        SessionChannel channel = new SessionChannel();
        channel.publishControl(channel.snapshot().withTurnStarted("r-1"));

        channel.advancePhase(List.of(
                message(1, MessageType.TEXT_DELTA, "我查一下"),
                message(2, MessageType.TOOL_CALL, "search")));

        assertThat(channel.snapshot().phase()).isEqualTo(TurnPhase.CALLING_TOOL);
    }

    @Test
    void 只有文本时推进到生成中() {
        SessionChannel channel = new SessionChannel();
        channel.publishControl(channel.snapshot().withTurnStarted("r-1"));

        channel.advancePhase(List.of(message(1, MessageType.TEXT_DELTA, "好的")));

        assertThat(channel.snapshot().phase()).isEqualTo(TurnPhase.WRITING);
    }

    @Test
    void turn未激活时不推进阶段_避免turn结束后又被拉回生成中() {
        SessionChannel channel = new SessionChannel();

        channel.advancePhase(List.of(message(1, MessageType.TEXT_DELTA, "迟到的帧")));

        assertThat(channel.snapshot().phase()).isEqualTo(TurnPhase.IDLE);
    }

    @Test
    void 系统消息不改变阶段() {
        SessionChannel channel = new SessionChannel();
        channel.publishControl(channel.snapshot().withTurnStarted("r-1").withPhase(TurnPhase.THINKING));

        channel.advancePhase(List.of(message(1, MessageType.SYSTEM, "已接管")));

        assertThat(channel.snapshot().phase()).isEqualTo(TurnPhase.THINKING);
    }

    @Test
    void 活跃turn的登记与清除() {
        SessionChannel channel = new SessionChannel();
        assertThat(channel.turnRunning()).isFalse();

        Disposable running = new FakeDisposable();
        channel.setActiveTurn(running);
        assertThat(channel.turnRunning()).isTrue();

        assertThat(channel.clearActiveTurn()).isSameAs(running);
        assertThat(channel.turnRunning()).isFalse();
    }

    @Test
    void 已释放的订阅不算在跑() {
        SessionChannel channel = new SessionChannel();
        FakeDisposable running = new FakeDisposable();
        channel.setActiveTurn(running);
        running.dispose();

        assertThat(channel.turnRunning()).isFalse();
    }

    @Test
    void 关闭时释放在跑的turn并结束两条流() {
        SessionChannel channel = new SessionChannel();
        FakeDisposable running = new FakeDisposable();
        channel.setActiveTurn(running);

        channel.close();

        assertThat(running.isDisposed()).isTrue();
        assertThat(channel.messages().collectList().block(TIMEOUT)).isEmpty();
    }

    private static ClientMessage message(long seq, MessageType type, String text) {
        return new ClientMessage(seq, "r-1", "b-1", MessageRole.ASSISTANT, type, text, Map.of(), AT);
    }

    private static final class FakeDisposable implements Disposable {

        private volatile boolean disposed;

        @Override
        public void dispose() {
            disposed = true;
        }

        @Override
        public boolean isDisposed() {
            return disposed;
        }
    }
}
