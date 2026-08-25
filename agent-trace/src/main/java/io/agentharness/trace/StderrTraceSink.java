package io.agentharness.trace;

import java.io.PrintStream;
import java.time.Instant;
import java.util.Objects;

/**
 * 把追踪打到一个 {@link PrintStream}（生产上就是 stderr）。
 *
 * <p>线程安全靠 {@link PrintStream#println(String)} 自身的同步 ——
 * 追踪会从多个 Reactor 线程同时进来，整行一次写出才不会互相穿插。
 * <b>不要改成分多次 print</b>。
 */
final class StderrTraceSink implements TraceSink {

    private final String component;
    private final PrintStream out;

    StderrTraceSink(String component, PrintStream out) {
        this.component = Objects.requireNonNull(component, "component");
        this.out = Objects.requireNonNull(out, "out");
    }

    @Override
    public void emit(TraceStage stage, String sessionId, String detail) {
        out.println(TraceFormat.line(Instant.now(), component, stage, sessionId, detail));
    }
}
