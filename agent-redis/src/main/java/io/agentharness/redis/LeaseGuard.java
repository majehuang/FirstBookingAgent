package io.agentharness.redis;

import io.agentharness.keys.KeyNamespace;
import io.agentharness.protocol.SessionRef;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话执行权。
 *
 * <p>三条必须守住的性质：
 * <ul>
 *   <li><b>{@code SET NX PX}</b> —— 抢占与设过期是一条命令，没有"抢到了但还没设过期就崩了"的窗口</li>
 *   <li><b>值每次唯一</b>（INV-3）—— 不能用 pod 名。同一个 pod 上的另一个 worker
 *       会因为值相同而误删他人的牌子，然后两个 worker 同时跑一个 session</li>
 *   <li><b>释放与续租都先比值</b> —— 自己的牌子已经过期、被别人抢走之后，
 *       朴素的 {@code DEL} 会删掉别人的。比值必须与删除原子，因此用 Lua</li>
 * </ul>
 */
public final class LeaseGuard {

    /**
     * 比值删除。
     *
     * <p>分成 GET 再 DEL 两条命令是不行的：两条之间牌子可能过期并被别人抢走，
     * 于是 DEL 删的是别人的牌子。这也正是"值每次唯一"必须成立的原因 ——
     * 值相同的话，比值这一步形同虚设。
     */
    private static final String RELEASE_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    /** 比值续租。同样必须原子，理由与释放一致。 */
    private static final String RENEW_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """;

    private final RedisRuntime runtime;

    public LeaseGuard(RedisRuntime runtime) {
        this.runtime = runtime;
    }

    /** 一次持有。{@code token} 是这次抢占独有的值，释放与续租都要拿它去比。 */
    public record Held(SessionRef session, String key, String token) {
    }

    public Mono<Optional<Held>> tryAcquire(SessionRef session, Duration ttl) {
        String key = KeyNamespace.lease(session.sessionId());
        String token = UUID.randomUUID().toString();

        return commands()
                .set(key, token, SetArgs.Builder.nx().px(ttl.toMillis()))
                .map(reply -> "OK".equals(reply)
                        ? Optional.of(new Held(session, key, token))
                        : Optional.<Held>empty())
                // SET NX 未抢到时 Lettuce 返回空 Mono，不是 "OK" 之外的值
                .defaultIfEmpty(Optional.empty());
    }

    public Mono<Boolean> renew(Held held, Duration ttl) {
        return commands()
                .<Long>eval(RENEW_SCRIPT, ScriptOutputType.INTEGER,
                        new String[]{held.key()}, held.token(), String.valueOf(ttl.toMillis()))
                .next()
                .map(changed -> changed != null && changed > 0)
                .defaultIfEmpty(false);
    }

    public Mono<Boolean> release(Held held) {
        return commands()
                .<Long>eval(RELEASE_SCRIPT, ScriptOutputType.INTEGER,
                        new String[]{held.key()}, held.token())
                .next()
                .map(deleted -> deleted != null && deleted > 0)
                .defaultIfEmpty(false);
    }

    /** 诊断用：当前牌子的值，没有则为空。 */
    public Mono<Optional<String>> peek(SessionRef session) {
        return commands().get(KeyNamespace.lease(session.sessionId()))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    private RedisReactiveCommands<String, String> commands() {
        return runtime.commands();
    }
}
