package io.agentharness.task.lease;

import io.agentharness.redis.LeaseGuard;
import io.agentharness.redis.PelHeartbeat;
import io.agentharness.task.dispatch.TaskTimings;

/**
 * 持牌期需要的四样东西，打包成一个参数。
 *
 * <p>它们是同一件事的四个面：{@link LeaseGuard} 管 lease 键，
 * {@link PelHeartbeat} 管消费组里那条 PEL 记录，{@link TaskTimings} 决定两者的节奏，
 * {@link ActiveTurns} 记着此刻手上攥着哪些执行权。
 *
 * <p>打包的实际收益是<b>共享</b>：节奏必须共享（续租与心跳同频是 INV-2c 的前提，
 * 分开传参迟早会有人只改其中一个），在飞表更必须共享 ——
 * Worker 往里登记、调度器停机时从里面取，两边拿到不同实例的话，
 * 停机会一个 turn 都交接不到，而且完全不报错。
 */
public record LeaseControl(LeaseGuard leases, PelHeartbeat heartbeat, TaskTimings timings,
                           ActiveTurns activeTurns) {

    public LeaseControl {
        if (leases == null || timings == null || activeTurns == null) {
            throw new IllegalArgumentException("leases、timings 与 activeTurns 不能为空");
        }
    }

    public LeaseControl(LeaseGuard leases, PelHeartbeat heartbeat, TaskTimings timings) {
        this(leases, heartbeat, timings, new ActiveTurns());
    }

    /**
     * 不带 PEL 心跳的持牌控制。
     *
     * <p>只用于<b>不经由消费组</b>的场景（单测里直接调 Worker、诊断命令）——
     * 那里根本没有令牌，也就没有 PEL 记录要刷。生产路径必须带心跳，
     * 否则长 turn 会被 {@code XAUTOCLAIM} 误回收。
     */
    public static LeaseControl withoutHeartbeat(LeaseGuard leases, TaskTimings timings) {
        return new LeaseControl(leases, null, timings, new ActiveTurns());
    }

    public boolean hasHeartbeat() {
        return heartbeat != null;
    }
}
