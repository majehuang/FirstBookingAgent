package io.agentharness.tui.port;

/**
 * 运行时开关链路追踪。
 *
 * <p>没有它的话追踪只能在启动时定：为了看一眼链路就得重启，
 * 重启就丢会话上下文 —— 而"重现一次"往往才是排查里最难的那步。
 */
public interface TraceControl {

    boolean enabled();

    /** 开或关。返回切换之后的状态。 */
    boolean setEnabled(boolean enabled);
}
