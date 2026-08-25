package io.agentharness.cli.redis;

import io.agentharness.comm.egress.MessageSubscriber;
import io.agentharness.comm.ingress.InstructionPublisher;
import io.agentharness.protocol.Ack;
import io.agentharness.protocol.ClientCapabilities;
import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.ControlFrame;
import io.agentharness.protocol.InstructionKind;
import io.agentharness.protocol.SessionRef;
import io.agentharness.protocol.UserInstruction;
import io.agentharness.store.message.MessageRepository;
import io.agentharness.tui.port.AgentBackend;
import io.agentharness.tui.port.Diagnostics;
import io.agentharness.tui.port.HistorySource;
import io.agentharness.tui.port.TraceControl;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

/**
 * 把收/发消息模块拼成 TUI 认识的后端。
 *
 * <p>这个适配器<b>住在组装根（agent-cli）而不是 agent-comm 里</b>：
 * 通信层不该知道有个终端界面存在 —— 换成 servlet 门面时，
 * 被替换掉的正是这一个类，agent-comm 一行不动。
 */
public final class RedisBackend implements AgentBackend, HistorySource {

    private final InstructionPublisher publisher;
    private final MessageSubscriber subscriber;
    private final MessageRepository repository;
    private final ClientCapabilities capabilities;
    private final String label;
    private final Diagnostics diagnostics;
    private final TraceControl traceControl;

    public RedisBackend(InstructionPublisher publisher, MessageSubscriber subscriber,
                        MessageRepository repository, ClientCapabilities capabilities) {
        this(publisher, subscriber, repository, capabilities, "redis", null, null);
    }

    public RedisBackend(InstructionPublisher publisher, MessageSubscriber subscriber,
                        MessageRepository repository, ClientCapabilities capabilities,
                        String label, Diagnostics diagnostics, TraceControl traceControl) {
        this.publisher = publisher;
        this.subscriber = subscriber;
        this.repository = repository;
        this.capabilities = capabilities == null ? ClientCapabilities.full() : capabilities;
        this.label = label == null ? "redis" : label;
        this.diagnostics = diagnostics;
        this.traceControl = traceControl;
    }

    @Override
    public String name() {
        return label;
    }

    @Override
    public Optional<Diagnostics> diagnostics() {
        return Optional.ofNullable(diagnostics);
    }

    @Override
    public Optional<TraceControl> traceControl() {
        return Optional.ofNullable(traceControl);
    }

    @Override
    public Flux<ClientMessage> messages(SessionRef session) {
        return subscriber.messages(session);
    }

    @Override
    public Flux<ControlFrame> control(SessionRef session) {
        return subscriber.control(session);
    }

    @Override
    public Mono<Ack> send(SessionRef session, UserInstruction instruction) {
        if (instruction.kind() == InstructionKind.CONTROL) {
            // 投递进 inbox 是能成功的，但 P1 的 Worker 只消费 msg 游标 ——
            // 那条指令会静静躺在队列里，用户以为停止了、实际没有。
            // 明确失败比静默无效好
            return Mono.error(new UnsupportedOperationException(
                    "跨节点打断在 P5 交付：指令会进 inbox，但 P1 的 Worker 还不消费控制游标"));
        }
        return publisher.publish(session, instruction);
    }

    @Override
    public Optional<HistorySource> history() {
        return Optional.of(this);
    }

    /**
     * 历史拉取。
     *
     * <p><b>这里也要降级。</b>只在消息流上做降级是不够的 —— 重开会话走的是历史拉取，
     * 仅文本客户端照样会拿到一条它渲染不了的 CARD。
     * 出站路径有两条，能力降级必须两条都覆盖。
     */
    @Override
    public List<ClientMessage> since(SessionRef session, long sinceSeq, int limit) {
        return repository.since(session, sinceSeq, limit).stream()
                .map(capabilities::degrade)
                .toList();
    }
}
