package io.agentharness.comm.ingress;

import io.agentharness.protocol.Ack;
import io.agentharness.protocol.SessionRef;
import io.agentharness.protocol.UserInstruction;
import reactor.core.publisher.Mono;

/**
 * 收消息模块的对外能力。HTTP 门面接上来之后，它就是 {@code POST /sessions/{sid}/messages} 的实现。
 */
public interface InstructionPublisher {

    /**
     * 投递一条指令。
     *
     * @return 两步 Redis 写入都成功后的回执；任一步失败则 Mono 以错误结束（对应 5xx）
     */
    Mono<Ack> publish(SessionRef session, UserInstruction instruction);
}
