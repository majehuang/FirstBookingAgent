package io.agentharness.cli;

import io.agentharness.trace.ToggleTraceSink;
import io.agentharness.tui.port.TraceControl;

import java.util.List;

/**
 * 把若干个可切换落点绑成一个开关。
 *
 * <p>默认形态下同一个进程里有两个落点：会话侧（写 inbox 那一环）与内嵌 worker 侧（其余五环）。
 * {@code /trace on} 必须两个一起开 —— 只开一个的话链路是断的，
 * 而"看到前半段、后半段没有"最容易被误判成后半段挂了。
 */
final class TraceSwitch implements TraceControl {

    private final List<ToggleTraceSink> sinks;

    TraceSwitch(List<ToggleTraceSink> sinks) {
        this.sinks = List.copyOf(sinks);
    }

    @Override
    public boolean enabled() {
        return sinks.stream().anyMatch(ToggleTraceSink::enabled);
    }

    @Override
    public boolean setEnabled(boolean enabled) {
        sinks.forEach(sink -> sink.setEnabled(enabled));
        return enabled;
    }
}
