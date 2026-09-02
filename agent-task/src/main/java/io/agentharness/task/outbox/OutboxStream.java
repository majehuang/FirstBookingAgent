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

import java.time.Duration;

/**
 * outbox 的写入端。
 *
 * <p>保留窗口按<b>时间</b>裁剪（{@code MINID}，P4-4），并受 {@code turnStartId}
 * 保护 —— turn 进行中不允许裁掉本 turn 的任何条目（INV-6）。
 * 窗口计算与保护的完整理由见 {@link OutboxRetention}。
 *
 * <p>裁剪挂在每次 {@code XADD} 上而不是独立任务：outbox 只在写入时增长，
 * 不写入时多留一会儿没有任何代价，专门的清理循环只会多一处会静默停跑的东西。
 */
public final class OutboxStream {

    private final RedisRuntime runtime;
    private final OutboxRetention retention;
    private final TraceSink trace;

    public OutboxStream(RedisRuntime runtime) {
        this(runtime, OutboxRetention.DEFAULT_WINDOW, TraceSink.disabled());
    }

    public OutboxStream(RedisRuntime runtime, Duration window, TraceSink trace) {
        this.runtime = runtime;
        this.retention = new OutboxRetention(window);
        this.trace = trace;
    }

    /**
     * turn 的第一条消息：写入并把它的条目 ID 登记为裁剪下限（INV-6）。
     *
     * <p>下限取<b>服务端返回的条目 ID</b> 而不是本地时钟 —— Stream ID 由 Redis
     * 的时钟分配，本地时钟偏快时用本地值当下限会把 turn 的第一条留在保护之外。
     */
    public Mono<String> publishTurnStart(SessionRef session, ClientMessage message) {
        return publish(session, message)
                .doOnNext(entryId -> retention.beginTurn(session.sessionId(), entryId));
    }

    /** turn 收尾（含失败与中止路径）：撤掉裁剪保护。 */
    public void endTurn(SessionRef session) {
        retention.endTurn(session.sessionId());
    }

    public Mono<String> publish(SessionRef session, ClientMessage message) {
        return Mono.defer(() -> runtime.commands().xadd(
                        KeyNamespace.outbox(session.sessionId()),
                        XAddArgs.Builder.minId(
                                retention.minId(session.sessionId(), System.currentTimeMillis())),
                        StreamPayload.of(message)))
                // 打的是落到流上的原始消息，不是摘要：
                // 客户端渲染出问题时，第一件要确认的事就是服务端到底发了什么
                .doOnNext(entryId -> trace.emit(TraceStage.MESSAGE_OUT, session.sessionId(),
                        () -> entryId + "  " + Json.write(message)));
    }
}
