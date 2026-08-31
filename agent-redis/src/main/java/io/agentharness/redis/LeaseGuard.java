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

    /** 摘牌脚本的资源名。启动时必须 {@code load} 它，否则第一次收尾就会失败。 */
    public static final String UNLEASE_SCRIPT = "unlease.lua";

    private final RedisRuntime runtime;
    private final ScriptRegistry scripts;

    public LeaseGuard(RedisRuntime runtime, ScriptRegistry scripts) {
        this.runtime = runtime;
        this.scripts = scripts;
    }

    /** 启动装配：加载摘牌脚本并缓存 SHA。 */
    public Mono<Void> loadScripts() {
        return scripts.load(UNLEASE_SCRIPT);
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

    /**
     * <b>裸释放：只比值删除，不看 inbox。</b>
     *
     * <p><b>用它来摘牌就是违反 INV-2</b>（会把释放瞬间到达的消息留成孤儿，
     * 完整推演见 {@code unlease.lua} 头部）。收尾一律用 {@link #unlease}。
     *
     * <p>只有两个合法调用点，两个都<b>不是</b>摘牌：
     * <ul>
     *   <li><b>停机交接</b>（{@code TurnHandoff}）—— 此时 inbox 里<i>必然</i>还有没跑完的活儿，
     *       {@link #unlease} 会正确地拒绝释放。但交接方紧接着会重投一个新的唤醒令牌，
     *       孤儿的成因（"牌子没了而唤醒也没了"）因此不成立。
     *       <b>顺序必须是先释放、后重投</b>：反过来的话，抢到新令牌的 pod 会在牌子还在时
     *       扑空，把那次唤醒白白交差掉</li>
     *   <li><b>INV-2 的反例实现</b> —— 混沌用例 CHA-002 先用它稳定造出孤儿，
     *       再换成 {@link #unlease} 证明孤儿消失。一条不变量如果没有能让它变红的实现，
     *       那条测试永远是绿的，也就什么都没证明</li>
     * </ul>
     */
    public Mono<Boolean> releaseIgnoringInbox(Held held) {
        return commands()
                .<Long>eval(RELEASE_SCRIPT, ScriptOutputType.INTEGER,
                        new String[]{held.key()}, held.token())
                .next()
                .map(deleted -> deleted != null && deleted > 0)
                .defaultIfEmpty(false);
    }

    /**
     * 摘牌（INV-2）：<b>校验持有 → 确认 inbox 已抽干 → 删除 lease</b>，三步在同一个 Lua 脚本内。
     *
     * <p>为什么必须原子、以及拆开会怎样，写在 {@code redis/unlease.lua} 的头部注释里。
     * 那段注释是这条不变量唯一的完整说明，改这个方法之前请先读它。
     *
     * <p>脚本只接受同一 session 的三个 key（lease / inbox / cursor），它们共用 hash tag
     * 因而必然同槽；全局的 ready 刻意不传进来 —— 传了在集群版上就是 CROSSSLOT。
     */
    public Mono<UnleaseOutcome> unlease(Held held) {
        String sessionId = held.session().sessionId();
        String[] keys = {
                held.key(),
                KeyNamespace.inbox(sessionId),
                KeyNamespace.cursor(sessionId)};

        return scripts.<Long>eval(UNLEASE_SCRIPT, ScriptOutputType.INTEGER, keys,
                        held.token(), Cursors.Kind.MSG.field())
                .map(UnleaseOutcome::fromCode)
                // 脚本必然返回整数；空 Mono 只可能来自连接层异常，
                // 当成"非持有者"是安全侧的选择 —— 它会阻止 XACK，令牌留给回收
                .defaultIfEmpty(UnleaseOutcome.NOT_HOLDER);
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
