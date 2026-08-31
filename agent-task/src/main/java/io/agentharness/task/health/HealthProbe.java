package io.agentharness.task.health;

import io.agentharness.keys.KeyNamespace;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.redis.XInfo;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.models.stream.PendingMessage;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 采一次健康快照。
 *
 * <p>四个全局数从 Redis 读，三个本机数由调用方注入 ——
 * 后者只有持牌进程自己知道（见 {@link QueueHealth} 的类注释）。
 *
 * <h2>都是只读命令，且失败不算故障</h2>
 * 探针在维护循环里定期跑，也可能被诊断命令随时调用。它<b>永远不该影响业务</b>：
 * 任何一项读不到就用降级值（{@code -1} / {@code null}）填上，绝不抛出。
 * 一个因为采不到指标而把维护循环带停的探针，比没有指标更糟。
 */
public final class HealthProbe {

    /** {@code XINFO GROUPS} 的字段名。 */
    private static final String FIELD_NAME = "name";
    private static final String FIELD_PENDING = "pending";
    private static final String FIELD_CONSUMERS = "consumers";

    /**
     * {@code lag} 是 Redis 7.0 才有的字段，而且在某些情况下 Redis 自己也算不出（返回 nil）。
     * 读不到就报 -1，不要伪造一个数 —— 指标上的假数比缺口更难查。
     */
    private static final String FIELD_LAG = "lag";

    private static final long UNKNOWN = -1L;

    private final RedisRuntime runtime;
    private final String group;
    private final Supplier<LocalState> localState;

    /**
     * 本机那三个数。
     *
     * @param inFlight    正在跑的 turn 数
     * @param capacity    在飞上限
     * @param longestHeld 持牌最久的那一轮已经持了多久，没有在飞时为 {@code null}
     */
    public record LocalState(int inFlight, int capacity, Duration longestHeld) {
    }

    public HealthProbe(RedisRuntime runtime, String group, Supplier<LocalState> localState) {
        this.runtime = runtime;
        this.group = group;
        this.localState = localState;
    }

    public Mono<QueueHealth> probe() {
        return groupInfo()
                .flatMap(info -> oldestPendingIdle().map(idle -> build(info, idle))
                        .switchIfEmpty(Mono.fromSupplier(() -> build(info, null))))
                .onErrorResume(error -> Mono.just(build(Map.of(), null)));
    }

    private QueueHealth build(Map<String, Object> info, Duration oldestIdle) {
        LocalState local = localState.get();
        return new QueueHealth(
                XInfo.numberOr(info.get(FIELD_LAG), UNKNOWN),
                XInfo.numberOr(info.get(FIELD_PENDING), UNKNOWN),
                oldestIdle,
                (int) XInfo.numberOr(info.get(FIELD_CONSUMERS), UNKNOWN),
                local.inFlight(),
                local.capacity(),
                local.longestHeld());
    }

    /** 找到我们那个组。别的组（比如测试用的隔离组）与本机健康无关。 */
    private Mono<Map<String, Object>> groupInfo() {
        return runtime.commands().xinfoGroups(KeyNamespace.READY)
                .map(XInfo::toFields)
                .filter(fields -> group.equals(XInfo.text(fields.get(FIELD_NAME))))
                .next()
                .defaultIfEmpty(Map.of());
    }

    /**
     * PEL 里最老那条的 idle。
     *
     * <p>取第一条即可：PEL 按 Stream ID 排序，而 ID 是递增的时间戳，
     * 所以第一条就是最早投递的那条。它的 idle 是<b>整个组里最长的等待</b> ——
     * 也就是"回收有没有在干活"最直接的读数。
     */
    private Mono<Duration> oldestPendingIdle() {
        return runtime.commands()
                .xpending(KeyNamespace.READY, group, Range.unbounded(), Limit.from(1))
                .next()
                .map(PendingMessage::getSinceLastDelivery);
    }
}
