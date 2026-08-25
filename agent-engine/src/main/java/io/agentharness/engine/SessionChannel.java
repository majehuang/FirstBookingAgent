package io.agentharness.engine;

import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.ControlFrame;
import io.agentharness.protocol.MessageType;
import io.agentharness.protocol.TurnPhase;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个 session 的两条出站流。
 *
 * <p>这是 outbox 与 ctrl-stream 的<b>进程内替身</b>：
 * <ul>
 *   <li>消息流用 {@code replay().limit(5min)} —— 对应 outbox 的保留窗口，新订阅者拿到窗口内全部消息</li>
 *   <li>控制流用 {@code replay().latest()} —— 对应"先下发快照，再接后续帧"</li>
 * </ul>
 *
 * <p>替身的边界很清楚：<b>只在同进程内有效</b>。
 * 换成 Redis Stream 之后才能跨 pod 扇出，那是 P1 的事。
 */
final class SessionChannel {

    private static final Duration OUTBOX_WINDOW = Duration.ofMinutes(5);

    private final Sinks.Many<ClientMessage> messages = Sinks.many().replay().limit(OUTBOX_WINDOW);
    private final Sinks.Many<ControlFrame> control = Sinks.many().replay().latest();
    private final AtomicReference<ControlFrame> current = new AtomicReference<>(ControlFrame.idle());
    private final AtomicReference<Disposable> activeTurn = new AtomicReference<>();
    private final AtomicReference<String> lastCtrlId = new AtomicReference<>("0");

    SessionChannel() {
        control.tryEmitNext(current.get());
    }

    Flux<ClientMessage> messages() {
        return messages.asFlux();
    }

    Flux<ControlFrame> control() {
        return control.asFlux();
    }

    ControlFrame snapshot() {
        return current.get();
    }

    void emit(ClientMessage message) {
        messages.tryEmitNext(message);
    }

    void publishControl(ControlFrame frame) {
        // 真实实现里 ctrlId 必须与 XADD 在同一个 Lua 脚本内产生（INV-11）。
        // 这里是进程内单写者，用递增计数占位；换成 Redis 时这一行要整体替换
        String ctrlId = String.valueOf(Long.parseLong(lastCtrlId.get()) + 1);
        lastCtrlId.set(ctrlId);
        ControlFrame stamped = frame.withCtrlId(ctrlId);
        current.set(stamped);
        control.tryEmitNext(stamped);
    }

    /** 按本批消息推进阶段展示。工具调用优先，因为它比"生成中"更能说明此刻在等什么。 */
    void advancePhase(List<ClientMessage> batch) {
        TurnPhase next = null;
        for (ClientMessage message : batch) {
            if (message.type() == MessageType.TOOL_CALL) {
                next = TurnPhase.CALLING_TOOL;
                break;
            }
            if (message.type() == MessageType.TEXT_DELTA) {
                next = TurnPhase.WRITING;
            }
        }
        ControlFrame snapshot = current.get();
        if (next != null && snapshot.phase() != next && snapshot.turnActive()) {
            publishControl(snapshot.withPhase(next));
        }
    }

    boolean turnRunning() {
        Disposable running = activeTurn.get();
        return running != null && !running.isDisposed();
    }

    void setActiveTurn(Disposable subscription) {
        activeTurn.set(subscription);
    }

    Disposable clearActiveTurn() {
        return activeTurn.getAndSet(null);
    }

    void close() {
        Disposable running = clearActiveTurn();
        if (running != null) {
            running.dispose();
        }
        messages.tryEmitComplete();
        control.tryEmitComplete();
    }
}
