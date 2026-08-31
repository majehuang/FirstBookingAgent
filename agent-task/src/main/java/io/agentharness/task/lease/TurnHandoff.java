package io.agentharness.task.lease;

import io.agentharness.keys.KeyNamespace;
import io.agentharness.redis.LeaseGuard;
import io.agentharness.redis.ReadyToken;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.redis.StreamLimits;
import io.agentharness.redis.StreamPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 停机交接：把一个跑不完的 turn 让给别的 pod，<b>不留等待</b>。
 *
 * <h2>为什么值得单独做这件事</h2>
 * 不做交接的话，停机与硬杀走的是同一条恢复路径：牌子等 30 秒 TTL 过期，
 * 令牌等 60 秒 idle + 最多 30 秒回收周期 —— <b>90 秒</b>。
 * 而"pod 死亡"这件事在生产里绝大多数是<b>发布</b>，不是崩溃。
 * 也就是说不做交接，等于给每次发布的每个在飞 session 都加上 90 秒的静默期。
 *
 * <p>做了交接，这个代价降到接近 0：新令牌立刻出现在 ready 里，
 * 任意空闲 pod 下一次轮询（50ms）就能捞到它。
 * 90 秒的 SLA 因此只覆盖真正的非预期死亡 —— 这正是 2026-08-26 敢采纳方案 A
 * （放宽 SLA 而不是调紧参数）的前提。
 *
 * <h2>四步，顺序全是有理由的</h2>
 * <pre>
 * ① 落闸       —— 停止一切写入，此后本 pod 不再是合法写入者
 * ② 释放 lease —— 按值比对删除
 * ③ 重投令牌   —— XADD 一个新的 ready
 * ④ 交差旧令牌 —— XACK
 * </pre>
 *
 * <p><b>②③ 不能颠倒。</b>先重投的话，抢到新令牌的 pod 会在牌子还没释放时去 tryAcquire，
 * 扑空之后判定为"有人正在处理"，把这次唤醒<b>交差掉</b>——
 * 唤醒就此消失，那个 session 还是要等 90 秒回收。这是本文件里最容易写反的一步。
 *
 * <p><b>③④ 也不能颠倒</b>，理由是崩溃安全：崩在 ③ 之后 ④ 之前，最坏是旧令牌later
 * 被回收、多产生一次唤醒 —— 重复唤醒本来就是设计允许的（幂等兜底）。
 * 反过来先交差再重投，崩在中间就<b>两个令牌都没有</b>，那个 session 彻底失联。
 *
 * <p>同理崩在 ② 之后 ③ 之前：牌子free了但没有新令牌，旧令牌仍在 PEL（还没 ④）——
 * 退化回 90 秒回收路径。**每一步崩溃的最坏结果都是"退化成原来的样子"，而不是丢消息。**
 *
 * <h2>④ 为什么可以交差一个没干完的工作</h2>
 * 表面上这违反 INV-4（没摘牌就不许 ACK）。实质不违反：INV-4 保护的是
 * <b>"通往这个 session 的路不能断"</b>，而 ③ 已经铺了一条新的、idle 从零开始的路。
 * 旧令牌此刻是纯粹的重复品，留着只会让回收在 60 秒后再唤醒一次。
 */
public final class TurnHandoff {

    private static final Logger log = LoggerFactory.getLogger(TurnHandoff.class);

    private final RedisRuntime runtime;
    private final LeaseGuard leases;
    private final String group;

    public TurnHandoff(RedisRuntime runtime, LeaseGuard leases, String group) {
        this.runtime = runtime;
        this.leases = leases;
        this.group = group;
    }

    /** 交接一个在飞 turn。失败不抛 —— 停机路径上抛异常只会让剩下的 turn 也交接不掉。 */
    public Mono<Void> handOff(ActiveTurns.Handle turn) {
        String sessionId = turn.session().sessionId();

        // ① 落闸。必须最先做：后面三步会让别的 pod 接手，
        //    而这个 pod 还有事件在管道里飞着，不止住就会两边同时写
        turn.fence().trip("pod 正在停机，本轮交接给其他节点");

        return leases.releaseIgnoringInbox(turn.lease())
                .doOnNext(released -> {
                    if (!released) {
                        // 牌子已经不是我们的了（续租失败过）。那就不该重投 ——
                        // 别人已经接管，重投只是制造一次多余的唤醒。但也无害
                        log.debug("session {} 交接时牌子已易主", sessionId);
                    }
                })
                .then(runtime.commands().xadd(KeyNamespace.READY, StreamLimits.ready(),
                        StreamPayload.of(ReadyToken.of(turn.session()))))
                .doOnNext(newToken -> log.info(
                        "session {} 已交接：释放执行权并重投唤醒令牌 {}（原令牌 {}）",
                        sessionId, newToken, turn.tokenId()))
                .then(runtime.commands().xack(KeyNamespace.READY, group, turn.tokenId()))
                .then()
                .onErrorResume(error -> {
                    // 交接失败就退化回回收路径：旧令牌还在 PEL 里（④ 没跑到），
                    // idle 到门槛之后会被别的 pod 捞走。慢，但不丢
                    log.error("session {} 交接失败，退回 PEL 回收路径（接管将慢至 SLA 上界）",
                            sessionId, error);
                    return Mono.empty();
                });
    }
}
