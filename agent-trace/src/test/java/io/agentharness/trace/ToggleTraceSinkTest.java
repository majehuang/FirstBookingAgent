package io.agentharness.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ToggleTraceSinkTest {

    private final List<String> emitted = new ArrayList<>();
    private final TraceSink recorder = (stage, sessionId, detail) -> emitted.add(detail);

    @Test
    void 关着时不落痕() {
        ToggleTraceSink sink = ToggleTraceSink.wrapping(recorder, false);

        sink.emit(TraceStage.INBOX_IN, "s-1", "不该出现");

        assertThat(sink.enabled()).isFalse();
        assertThat(emitted).isEmpty();
    }

    @Test
    void 开了之后落痕() {
        ToggleTraceSink sink = ToggleTraceSink.wrapping(recorder, false);

        assertThat(sink.setEnabled(true)).isTrue();
        sink.emit(TraceStage.INBOX_IN, "s-1", "该出现");

        assertThat(emitted).containsExactly("该出现");
    }

    @Test
    @DisplayName("关着时连 detail 都不求值 —— 那一环要序列化整条消息，白做一次不能接受")
    void 关着时惰性参数不求值() {
        ToggleTraceSink sink = ToggleTraceSink.wrapping(recorder, false);
        AtomicInteger evaluations = new AtomicInteger();

        sink.emit(TraceStage.MESSAGE_OUT, "s-1", () -> {
            evaluations.incrementAndGet();
            return "贵的序列化";
        });

        assertThat(evaluations.get()).isZero();

        sink.setEnabled(true);
        sink.emit(TraceStage.MESSAGE_OUT, "s-1", () -> {
            evaluations.incrementAndGet();
            return "贵的序列化";
        });

        assertThat(evaluations.get()).isOne();
    }

    @Test
    void 可以来回切() {
        ToggleTraceSink sink = ToggleTraceSink.wrapping(recorder, true);

        sink.emit(TraceStage.INBOX_IN, "s-1", "一");
        sink.setEnabled(false);
        sink.emit(TraceStage.INBOX_IN, "s-1", "二");
        sink.setEnabled(true);
        sink.emit(TraceStage.INBOX_IN, "s-1", "三");

        assertThat(emitted).containsExactly("一", "三");
    }

    @Test
    void 打到stderr的那个也能开关() {
        assertThat(ToggleTraceSink.toStderr("tui", false).enabled()).isFalse();
        assertThat(ToggleTraceSink.toStderr("tui", true).enabled()).isTrue();
    }
}
