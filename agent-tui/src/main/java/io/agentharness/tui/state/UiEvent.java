package io.agentharness.tui.state;

import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.ControlFrame;

import java.time.Instant;

/**
 * 驱动 UI 状态变化的事件。密封类型 —— 新增一种事件时 reducer 的 switch 会编译报错，
 * 逼着调用方补上处理分支，而不是运行时静默走 default。
 */
public sealed interface UiEvent {

    record MessageArrived(ClientMessage message, Instant at) implements UiEvent {
    }

    record ControlArrived(ControlFrame frame, Instant at) implements UiEvent {
    }

    record ConnectionChanged(ConnectionState connection, Instant at) implements UiEvent {
    }

    /** 定时刷新，用于状态行上的耗时计数。 */
    record Tick(Instant at) implements UiEvent {
    }
}
