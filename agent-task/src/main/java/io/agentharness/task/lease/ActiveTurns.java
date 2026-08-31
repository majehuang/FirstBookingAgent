package io.agentharness.task.lease;

import io.agentharness.protocol.SessionRef;
import io.agentharness.redis.LeaseGuard;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本 pod 当前持牌在跑的 turn。
 *
 * <p><b>只为优雅停机而存在。</b>正常运行时没有任何代码读它 —— 每个 turn 自己管自己的
 * lease 与令牌，不需要一张全局表。但停机那一刻需要回答一个别处答不上来的问题：
 * <b>"我手上还攥着哪些 session 的执行权和令牌？"</b>
 *
 * <p>没有这张表，停机就只能靠取消订阅把在飞 turn 硬掐掉：牌子留着等 30 秒 TTL、
 * 令牌留在 PEL 里等 60 秒 idle + 30 秒回收周期。也就是说
 * <b>每次发布都会让每个在飞 session 吃满 90 秒</b> —— 而发布是最频繁的一种"pod 死亡"。
 *
 * <p>键用 lease token 而不是 sessionId：token 每次抢占唯一（INV-3），
 * 而 sessionId 在理论上可能同时出现两条记录（旧 turn 尚未清理、新 turn 已开始）。
 * 用 sessionId 当键的话，后者会把前者从表里挤掉，于是停机时漏交接一个。
 */
public final class ActiveTurns {

    /**
     * 一个在飞 turn 的交接所需的全部信息。
     *
     * @param session 会话，用于重投唤醒令牌
     * @param lease   持有的执行权，交接时按值比对释放
     * @param tokenId ready 流里那条令牌的 ID，交接完成后交差
     * @param fence   执行权闸门，交接第一步就是落闸止写
     * @param heldSince 抢到牌子的时刻。<b>持牌上限的判据，也是健康探针读的那个数</b> ——
     *                  两者读同一个值，免得判定与观测各算各的
     */
    public record Handle(SessionRef session, LeaseGuard.Held lease, String tokenId,
                         LeaseFence fence, Instant heldSince) {

        /** 已经持牌多久。 */
        public Duration heldFor() {
            return Duration.between(heldSince, Instant.now());
        }
    }

    private final Map<String, Handle> inFlight = new ConcurrentHashMap<>();

    public Handle register(SessionRef session, LeaseGuard.Held lease, String tokenId,
                           LeaseFence fence) {
        Handle handle = new Handle(session, lease, tokenId, fence, Instant.now());
        inFlight.put(lease.token(), handle);
        return handle;
    }

    /** 必须在 {@code doFinally} 里调 —— 漏掉就会在停机时交接一个早已结束的 turn。 */
    public void unregister(Handle handle) {
        inFlight.remove(handle.lease().token());
    }

    /**
     * 当前快照。
     *
     * <p>返回副本而不是视图：调用方（停机流程）会一边遍历一边让这些 turn 终止，
     * 而终止会触发 {@code unregister} 改动底层 map。
     */
    public List<Handle> snapshot() {
        Collection<Handle> current = inFlight.values();
        return List.copyOf(current);
    }

    public int size() {
        return inFlight.size();
    }

    /**
     * 持牌最久的那一轮已经持了多久；没有在飞时返回 {@code null}。
     *
     * <p>健康探针用它回答"有没有 turn 正在卡死"——
     * 这个数只有持牌进程自己知道，Redis 那边看不出来（牌子一直在续，看着很健康）。
     */
    public Duration longestHeld() {
        return inFlight.values().stream()
                .map(Handle::heldFor)
                .max(Duration::compareTo)
                .orElse(null);
    }
}
