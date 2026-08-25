package io.agentharness.trace;

import java.util.function.Supplier;

/**
 * 链路追踪的落点。
 *
 * <p><b>默认关闭。</b>热路径上每条消息都会走过这里，
 * 开着的代价是每条消息多几次字符串拼接加一次 IO —— 平时不值得。
 *
 * <p>关闭时 {@link #enabled()} 返回 {@code false}，配合 {@link #emit(TraceStage, String, Supplier)}
 * 的惰性重载，连拼接都不会发生。<b>调用方请一律用惰性重载</b>，
 * 直接传字符串的那个只适合本来就现成的值。
 */
public interface TraceSink {

    /** 落一条追踪。 */
    void emit(TraceStage stage, String sessionId, String detail);

    /** 是否开启。关闭时调用方应当跳过一切为追踪而做的计算。 */
    default boolean enabled() {
        return true;
    }

    /**
     * 惰性版本：关闭时 {@code detail} 根本不会被求值。
     *
     * <p>这不是微优化 —— 「进入 outbox 的原始指令」这一环要把整条消息序列化，
     * 关着的时候白做一次序列化是不能接受的。
     */
    default void emit(TraceStage stage, String sessionId, Supplier<String> detail) {
        if (enabled()) {
            emit(stage, sessionId, detail.get());
        }
    }

    /** 什么都不做，且 {@link #enabled()} 为 false。 */
    static TraceSink disabled() {
        return NoopTraceSink.INSTANCE;
    }

    /**
     * 打到 stderr。
     *
     * <p><b>刻意不走 stdout。</b>逐行模式的 stdout 是可 diff 的验收产物，
     * 掺进追踪就没法再拿来比对了。而在交互式终端上两者都可见，
     * 所以"看得见"这个目的并不受影响。
     *
     * @param component 进程名，用于区分并排的两个终端（如 {@code tui} / {@code worker}）
     */
    static TraceSink toStderr(String component) {
        return new StderrTraceSink(component, System.err);
    }
}
