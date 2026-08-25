package io.agentharness.task.outbox;

import io.agentharness.keys.KeyNamespace;
import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.Json;
import io.agentharness.protocol.SessionRef;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.redis.StreamPayload;
import io.agentharness.trace.TraceSink;
import io.agentharness.trace.TraceStage;
import io.lettuce.core.XAddArgs;
import reactor.core.publisher.Mono;

/**
 * outbox 的写入端。
 *
 * <p>保留窗口按条数近似裁剪。精确的按时间裁剪与 {@code turnStartId} 保护
 * （turn 进行中不允许裁剪，INV-6）属于 P4 —— 在那之前，窗口设得足够大以避免
 * 把正在进行的 turn 的前半段裁掉。
 */
public final class OutboxStream {

    private static final long DEFAULT_MAX_LEN = 10_000L;

    private final RedisRuntime runtime;
    private final long maxLen;
    private final TraceSink trace;

    public OutboxStream(RedisRuntime runtime) {
        this(runtime, DEFAULT_MAX_LEN, TraceSink.disabled());
    }

    public OutboxStream(RedisRuntime runtime, long maxLen) {
        this(runtime, maxLen, TraceSink.disabled());
    }

    public OutboxStream(RedisRuntime runtime, long maxLen, TraceSink trace) {
        this.runtime = runtime;
        this.maxLen = maxLen;
        this.trace = trace;
    }

    public Mono<String> publish(SessionRef session, ClientMessage message) {
        return runtime.commands().xadd(
                        KeyNamespace.outbox(session.sessionId()),
                        XAddArgs.Builder.maxlen(maxLen).approximateTrimming(),
                        StreamPayload.of(message))
                // 打的是落到流上的原始消息，不是摘要：
                // 客户端渲染出问题时，第一件要确认的事就是服务端到底发了什么
                .doOnNext(entryId -> trace.emit(TraceStage.MESSAGE_OUT, session.sessionId(),
                        () -> entryId + "  " + Json.write(message)));
    }
}
