package io.agentharness.cli.redis;

import io.agentharness.comm.egress.MessageSubscriber;
import io.agentharness.comm.ingress.InstructionPublisher;
import io.agentharness.protocol.Ack;
import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.ControlFrame;
import io.agentharness.protocol.InstructionKind;
import io.agentharness.protocol.SessionRef;
import io.agentharness.protocol.UserInstruction;
import io.agentharness.store.message.MessageRepository;
import io.agentharness.tui.port.AgentBackend;
import io.agentharness.tui.port.HistorySource;
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

    public RedisBackend(InstructionPublisher publisher, MessageSubscriber subscriber,
                        MessageRepository repository) {
        this.publisher = publisher;
        this.subscriber = subscriber;
        this.repository = repository;
    }

    @Override
    public String name() {
        return "redis";
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

    @Override
    public List<ClientMessage> since(SessionRef session, long sinceSeq, int limit) {
        return repository.since(session, sinceSeq, limit);
    }
}
