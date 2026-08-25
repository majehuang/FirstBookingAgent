package io.agentharness.task.worker;

import io.agentharness.keys.KeyNamespace;
import io.agentharness.protocol.ControlFrame;
import io.agentharness.protocol.Json;
import io.agentharness.protocol.SessionRef;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.trace.TraceSink;
import io.agentharness.trace.TraceStage;
import io.lettuce.core.ScriptOutputType;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 控制状态的写入端。
 *
 * <p><b>水位必须与 {@code XADD} 在同一个 Lua 脚本内产生（INV-11）。</b>
 * 分成两条命令的话，快照代表 T2 的状态、而重放却从 T1 开始，
 * 旧帧会覆盖新状态 —— 客户端反复断线重连时会看到 {@code turnActive} 先 false 再变回 true，
 * 也就是<b>状态翻转</b>。这类 bug 只在重连时机恰好落在两条命令之间时出现，
 * 平时怎么测都是好的。
 *
 * <p>脚本内用 {@code XADD ... *} 生成非确定性 ID。P0-1 已在目标版本的 Redis 上实测通过；
 * 换实例后需要用 {@code agent doctor} 重新确认。
 */
public final class ControlPublisher {

    /**
     * 三步原子：追加帧 → 取回条目 ID 作为水位 → 水位写回快照。
     *
     * <p>流里那一帧<b>不</b>带 ctrlId：条目 ID 本身就是水位，
     * 再存一份只会多出一个可能不一致的来源。读取侧从条目 ID 补上。
     */
    private static final String PUBLISH_SCRIPT = """
            local id = redis.call('XADD', KEYS[2], 'MAXLEN', '~', ARGV[3], '*', 'd', ARGV[1])
            local frame = cjson.decode(ARGV[1])
            frame.ctrlId = id
            local snapshot = cjson.encode(frame)
            redis.call('SET', KEYS[1], snapshot, 'EX', tonumber(ARGV[2]))
            redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2]))
            return snapshot
            """;

    private static final long CTRL_STREAM_MAX_LEN = 200L;
    private static final Duration DEFAULT_TTL = Duration.ofHours(12);

    private final RedisRuntime runtime;
    private final Duration ttl;
    private final TraceSink trace;

    public ControlPublisher(RedisRuntime runtime) {
        this(runtime, DEFAULT_TTL, TraceSink.disabled());
    }

    public ControlPublisher(RedisRuntime runtime, Duration ttl) {
        this(runtime, ttl, TraceSink.disabled());
    }

    public ControlPublisher(RedisRuntime runtime, Duration ttl, TraceSink trace) {
        this.runtime = runtime;
        this.ttl = ttl;
        this.trace = trace;
    }

    /** @return 写入后的快照，含刚刚产生的 ctrlId 水位 */
    public Mono<ControlFrame> publish(SessionRef session, ControlFrame frame) {
        // ctrlId 由脚本产生，入参里不能带 —— 带了会被脚本覆盖，徒增困惑
        String json = Json.write(frame.withCtrlId(null));

        return runtime.commands()
                .<String>eval(PUBLISH_SCRIPT, ScriptOutputType.VALUE,
                        new String[]{
                                KeyNamespace.state(session.sessionId()),
                                KeyNamespace.ctrlStream(session.sessionId())
                        },
                        json, String.valueOf(ttl.toSeconds()), String.valueOf(CTRL_STREAM_MAX_LEN))
                .next()
                // 打脚本返回的快照而不是入参：只有它带着刚生成的 ctrlId 水位，
                // 而水位正是这条链路最需要盯的东西（INV-11）
                .doOnNext(snapshot -> trace.emit(TraceStage.CTRL_OUT, session.sessionId(),
                        () -> snapshot))
                .map(snapshot -> Json.read(snapshot, ControlFrame.class));
    }
}
