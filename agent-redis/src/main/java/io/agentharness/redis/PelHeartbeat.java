package io.agentharness.redis;

import io.agentharness.keys.KeyNamespace;
import io.lettuce.core.Consumer;
import io.lettuce.core.XClaimArgs;
import reactor.core.publisher.Mono;

/**
 * 持牌期的 PEL 心跳（INV-2c）：{@code XCLAIM ready <group> <自己> 0 <令牌ID> JUSTID}。
 *
 * <p><b>没有它，回收机制就没法既快又安全。</b>{@code XAUTOCLAIM} 判断一个令牌是不是
 * 死掉了，看的是它在 PEL 里的 idle 时间；而 idle 只在投递或认领时归零。
 * 一个正常跑着的长 turn，从 Redis 的角度看和一个崩掉的 pod 完全一样 ——
 * 都是"取走了令牌之后再没消息"。
 *
 * <p>于是没有心跳时只有两个选择：把 {@code MIN-IDLE-TIME} 设成大于最长 turn（分钟级，
 * 恢复时延跟着变成分钟级），或者按秒回收（把正在干活的 pod 的令牌抢走，制造双跑与 churn）。
 * 心跳让这两者解耦：<b>idle 反映的是"这个 pod 还活着吗"，不再是"这轮跑完了吗"。</b>
 *
 * <h2>为什么是 JUSTID</h2>
 * 不带 {@code JUSTID} 的 {@code XCLAIM} 会把消息体一起返回，并且<b>递增投递计数</b>。
 * 投递计数是"这条消息被重新投递过几次"的语义，运维会拿它做重试告警 ——
 * 每 10 秒给自己加一次的话，一个跑了十分钟的正常 turn 会显示被重投了 60 次。
 * {@code JUSTID} 只重置 idle，不动计数，正是为这个场景准备的。
 *
 * <h2>为什么 MIN-IDLE 传 0</h2>
 * 这里认领的是<b>自己已经持有的</b>令牌，不存在抢别人的问题，所以不需要门槛。
 * （{@code XAUTOCLAIM} 那边就完全相反 —— 那里填 0 会抢走存活 pod 的令牌。）
 */
public final class PelHeartbeat {

    /** 认领自己的令牌不需要门槛，见类注释。 */
    private static final long NO_MIN_IDLE = 0L;

    private final RedisRuntime runtime;
    private final String group;
    private final String consumerName;

    public PelHeartbeat(RedisRuntime runtime, String group, String consumerName) {
        this.runtime = runtime;
        this.group = group;
        this.consumerName = consumerName;
    }

    /**
     * 给一个令牌续 idle。
     *
     * <p>只刷自己名下的、且仍在 pending 的那个 ID（HBT-005）：{@code XCLAIM} 不带
     * {@code FORCE} 时，已经 {@code XACK} 过的 ID 不会被重新创建成 pending，
     * 命令只是返回空 —— 所以 turn 结束后万一多打了一拍，也不会把令牌复活（HBT-006）。
     *
     * @param tokenId ready 流里的条目 ID
     * @return 是否确实刷新了（令牌已不在 PEL 时为 false）
     */
    public Mono<Boolean> touch(String tokenId) {
        return runtime.commands()
                .xclaim(KeyNamespace.READY,
                        Consumer.from(group, consumerName),
                        XClaimArgs.Builder.minIdleTime(NO_MIN_IDLE).justid(),
                        tokenId)
                .hasElements();
    }

    public String consumerName() {
        return consumerName;
    }
}
