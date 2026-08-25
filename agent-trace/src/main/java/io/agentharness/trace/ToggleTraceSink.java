package io.agentharness.trace;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 可以在运行时开关的追踪落点。
 *
 * <p>存在的理由是 {@code /trace}：排查时想看链路，而追踪原本只能在启动时定 ——
 * 为了看一眼就得重启，重启就丢会话上下文，而"重现一次"往往才是最难的那步。
 *
 * <p>{@link #enabled()} 每次都读一遍开关，因此关着的时候
 * {@link TraceSink#emit(TraceStage, String, java.util.function.Supplier)} 的惰性重载照旧不求值 ——
 * 热路径上的代价与 {@link TraceSink#disabled()} 相同。
 */
public final class ToggleTraceSink implements TraceSink {

    private final TraceSink delegate;
    private final AtomicBoolean on;

    private ToggleTraceSink(TraceSink delegate, boolean initiallyOn) {
        this.delegate = delegate;
        this.on = new AtomicBoolean(initiallyOn);
    }

    /** 包一个已有的落点。 */
    public static ToggleTraceSink wrapping(TraceSink delegate, boolean initiallyOn) {
        return new ToggleTraceSink(delegate, initiallyOn);
    }

    /** 打到 stderr 的可切换落点。 */
    public static ToggleTraceSink toStderr(String component, boolean initiallyOn) {
        return new ToggleTraceSink(TraceSink.toStderr(component), initiallyOn);
    }

    @Override
    public boolean enabled() {
        return on.get();
    }

    @Override
    public void emit(TraceStage stage, String sessionId, String detail) {
        if (on.get()) {
            delegate.emit(stage, sessionId, detail);
        }
    }

    /** 开或关。返回<b>切换之后</b>的状态。 */
    public boolean setEnabled(boolean enabled) {
        on.set(enabled);
        return enabled;
    }
}
