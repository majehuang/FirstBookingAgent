package io.agentharness.task.outbox;

import io.agentharness.keys.KeyNamespace;
import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.SessionRef;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.redis.StreamPayload;
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

    public OutboxStream(RedisRuntime runtime) {
        this(runtime, DEFAULT_MAX_LEN);
    }

    public OutboxStream(RedisRuntime runtime, long maxLen) {
        this.runtime = runtime;
        this.maxLen = maxLen;
    }

    public Mono<String> publish(SessionRef session, ClientMessage message) {
        return runtime.commands().xadd(
                KeyNamespace.outbox(session.sessionId()),
                XAddArgs.Builder.maxlen(maxLen).approximateTrimming(),
                StreamPayload.of(message));
    }
}
