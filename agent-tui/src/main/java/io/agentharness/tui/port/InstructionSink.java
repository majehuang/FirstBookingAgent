package io.agentharness.tui.port;

import io.agentharness.protocol.Ack;
import io.agentharness.protocol.SessionRef;
import io.agentharness.protocol.UserInstruction;
import reactor.core.publisher.Mono;

/**
 * 收消息模块的客户端视角。
 *
 * <p>实现方必须保证 INV-1：先 {@code XADD inbox}、后 {@code XADD ready}，
 * 两条都成功才发出 Ack。失败时调用方带同一个 instructionId 重试，
 * 重复由消费侧幂等检查吃掉。
 */
public interface InstructionSink {

    Mono<Ack> send(SessionRef session, UserInstruction instruction);
}
