package io.agentharness.redis;

import io.agentharness.keys.KeyNamespace;
import reactor.core.publisher.Mono;

/**
 * 双游标：{@code msg} 与 {@code ctrl}。
 *
 * <p>消息与控制指令统一进 inbox，靠这两个游标区分消费时机 —— 一套顺序、一套持久化、
 * 一套故障恢复（开发规划 G 节）。
 *
 * <p><b>刻意不设 TTL。</b>游标先于 inbox 过期的话，空闲久的 session 被唤醒会从头
 * 重放全部历史指令 —— 用户会突然收到一串早已回答过的问题的新回复。
 * 随 session 一起显式清理，是开发规划 C 节点名的那个陷阱。
 */
public final class Cursors {

    /** Stream 的起点。XREAD 从它开始读的是"比它更新的"，所以初始值读得到全部。 */
    public static final String BEGINNING = "0-0";

    public enum Kind {
        MSG("msg"),
        CTRL("ctrl");

        private final String field;

        Kind(String field) {
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    private final RedisRuntime runtime;

    public Cursors(RedisRuntime runtime) {
        this.runtime = runtime;
    }

    public Mono<String> read(String sessionId, Kind kind) {
        return runtime.commands()
                .hget(KeyNamespace.cursor(sessionId), kind.field())
                .defaultIfEmpty(BEGINNING);
    }

    public Mono<Void> advance(String sessionId, Kind kind, String streamId) {
        return runtime.commands()
                .hset(KeyNamespace.cursor(sessionId), kind.field(), streamId)
                .then();
    }
}
