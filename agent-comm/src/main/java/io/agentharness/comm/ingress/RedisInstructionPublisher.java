package io.agentharness.comm.ingress;

import io.agentharness.keys.KeyNamespace;
import io.agentharness.protocol.Ack;
import io.agentharness.protocol.Json;
import io.agentharness.protocol.SessionRef;
import io.agentharness.protocol.UserInstruction;
import io.agentharness.redis.ReadyToken;
import io.agentharness.redis.RedisException;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.redis.StreamLimits;
import io.agentharness.redis.StreamPayload;
import io.agentharness.store.message.MessageRepository;
import io.agentharness.trace.TraceSink;
import io.agentharness.trace.TraceStage;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

/**
 * 投递实现。<b>三步的顺序是这个类的全部内容，改动前请先读完这段。</b>
 *
 * <pre>
 * ① XADD inbox   ← 必须先
 * ② XADD ready   ← 后
 * ③ 落 USER 消息（幂等）
 * ④ 回执
 * </pre>
 *
 * <p><b>为什么 ①② 不可颠倒（INV-1）</b>：摘牌脚本释放执行权前会确认 inbox 已抽干。
 * 先写 ready 的话，Worker 可能在 inbox 那条还没写进去时就被唤醒、发现队列是空的、
 * 然后放掉执行权 —— 那条消息就永远没人处理了，而且没有任何报错。
 *
 * <p><b>为什么单个 ready 与分片 inbox 无法原子</b>：ready 刻意不分片（每个 pod 只监听一条 stream），
 * 与 {@code {sNNN}} 分片的 inbox 必然不同槽，Lua 做不到。所以投递可靠性被有意挪到了
 * 客户端重试约定上：没收到回执就带同一个 {@code instructionId} 重试。
 *
 * <p><b>为什么落库排在两次 XADD 之后</b>：反过来的话，inbox 写失败会在消息表里留下一条
 * 永远等不到回复的 USER 消息 —— 用户看到自己的话出现了，然后什么都没发生。
 * 排在后面则最坏只是"指令进了队列但回执没返回"，客户端重试即可自愈。
 *
 * <p><b>为什么 Worker 也会落同一条 USER 消息</b>：②③ 之间 Worker 可能已经把指令捞走了。
 * 两边都调用幂等的 {@code appendUserMessage}，谁先到谁分配序号，另一方拿到同一条。
 */
public final class RedisInstructionPublisher implements InstructionPublisher {

    private final RedisRuntime runtime;
    private final MessageRepository repository;
    private final TraceSink trace;

    public RedisInstructionPublisher(RedisRuntime runtime, MessageRepository repository) {
        this(runtime, repository, TraceSink.disabled());
    }

    public RedisInstructionPublisher(RedisRuntime runtime, MessageRepository repository,
                                     TraceSink trace) {
        this.runtime = runtime;
        this.repository = repository;
        this.trace = trace;
    }

    @Override
    public Mono<Ack> publish(SessionRef session, UserInstruction instruction) {
        String inboxKey = KeyNamespace.inbox(session.sessionId());

        return runtime.commands()
                .xadd(inboxKey, StreamLimits.inbox(), StreamPayload.of(instruction))
                .onErrorMap(e -> new RedisException("写 inbox 失败，指令未被接受", e))

                // 追踪落在 XADD 之后：写成功了才算"进了 inbox"。
                // 放在前面的话，失败的那次也会留痕，反倒把排查引向错误的方向
                .doOnNext(entryId -> trace.emit(TraceStage.INBOX_IN, session.sessionId(),
                        () -> entryId + "  " + Json.write(instruction)))

                // ② ready 在后。这一步失败时 inbox 里已经有指令了，
                //    但没有唤醒令牌 —— 靠客户端带同一个 instructionId 重试来补
                .then(runtime.commands()
                        .xadd(KeyNamespace.READY, StreamLimits.ready(),
                                StreamPayload.of(ReadyToken.of(session)))
                        .onErrorMap(e -> new RedisException("写 ready 失败，未产生唤醒", e)))

                // ③ 两步都成功之后才落库。阻塞 JDBC，必须 offload（INV-7）
                .then(Mono.fromCallable(() -> repository.appendUserMessage(
                                session, newReplyId(), "u-" + instruction.instructionId(),
                                instruction.text(), instruction.instructionId()))
                        .subscribeOn(Schedulers.boundedElastic()))

                .map(outcome -> new Ack(instruction.instructionId(),
                        outcome.message().replyId(), outcome.message().msgSeq()));
    }

    private static String newReplyId() {
        return "r-" + UUID.randomUUID().toString().substring(0, 12);
    }
}
