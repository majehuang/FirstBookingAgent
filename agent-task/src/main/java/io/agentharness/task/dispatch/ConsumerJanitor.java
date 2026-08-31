package io.agentharness.task.dispatch;

import io.agentharness.keys.KeyNamespace;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.redis.XInfo;
import io.lettuce.core.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalLong;

/**
 * 死 consumer 清理（INV-2d）。
 *
 * <h2>{@code pending = 0} 不是优化，是安全前提</h2>
 * {@code XGROUP DELCONSUMER} 会<b>连同该消费者的 pending 记录一起销毁</b>。
 * 那些条目随后既不在任何 PEL 里、也不是新条目 —— 从消费组的视角<b>彻底消失</b>，
 * 而命令本身返回成功、没有任何报错，只是返回值里带着"顺手删了几条 pending"。
 *
 * <p>换句话说，在有 pending 时删消费者，等于把那几条工作<b>无声蒸发</b>掉。
 * 之后连回收都救不回来：{@code XAUTOCLAIM} 只能扫 PEL，而它们已经不在 PEL 里了。
 *
 * <h2>顺序必须是「先回收，后清理」</h2>
 * 反过来的话，清理会先看到"这个 consumer 还有 pending"于是跳过它，
 * 而那些 pending 本来正是回收要捞走的。先回收把工作捞干净，
 * 消费者的 pending 归零，然后才轮到清理它的元数据（CLN-004）。
 * 这个顺序由 {@link MaintenanceCycle} 保证，不在这个类里。
 *
 * <h2>为什么门槛是 1 小时而不是几分钟</h2>
 * 清理的收益只是元数据不堆积，而清理错了的代价是丢工作。这种收益/代价比例下，
 * 门槛应该定得让人放心而不是让人高效。1 小时意味着：一个 pod 就算滚动更新时
 * 卡了半小时，它的 consumer 也不会被别人清掉（CLN-007）。
 */
public final class ConsumerJanitor {

    private static final Logger log = LoggerFactory.getLogger(ConsumerJanitor.class);

    /** {@code XINFO CONSUMERS} 返回的字段名。 */
    private static final String FIELD_NAME = "name";
    private static final String FIELD_PENDING = "pending";
    private static final String FIELD_IDLE = "idle";

    private final RedisRuntime runtime;
    private final String group;
    private final ConsumerName self;
    private final TaskTimings timings;

    public ConsumerJanitor(RedisRuntime runtime, String group, ConsumerName self,
                           TaskTimings timings) {
        this.runtime = runtime;
        this.group = group;
        this.self = self;
        this.timings = timings;
    }

    /**
     * 扫一遍消费组，删掉确实死透了的消费者。
     *
     * @return 实际删除的个数
     */
    public Mono<Integer> sweep() {
        return runtime.commands().xinfoConsumers(KeyNamespace.READY, group)
                .map(XInfo::toFields)
                .filter(this::isSafeToDelete)
                .concatMap(this::delete)
                .reduce(0, Integer::sum)
                .onErrorResume(error -> {
                    // 清理失败无害 —— 元数据多留一轮而已。
                    // 但不能让它把整个维护循环带停，那会连回收一起停掉
                    log.warn("死 consumer 清理失败，本轮跳过", error);
                    return Mono.just(0);
                });
    }

    /**
     * 两个条件<b>必须同时</b>满足。
     *
     * <p>用严格大于而不是大于等于（CLN-002）：恰好等于阈值时不删。
     * 差别只有一个采样周期，但它让"边界值"这个词有确定含义 ——
     * 而秒/毫秒换算写错时，恰好卡在边界的那次就是最早暴露的信号。
     */
    private boolean isSafeToDelete(Map<String, Object> fields) {
        String name = XInfo.text(fields.get(FIELD_NAME));
        if (name == null || name.equals(self.value())) {
            // 不删自己。活着的进程删掉自己的 consumer 元数据之后，
            // 下一次 XREADGROUP 会重新把它建出来，纯属白折腾
            return false;
        }

        OptionalLong pending = XInfo.number(fields.get(FIELD_PENDING));
        if (pending.isEmpty()) {
            // 读不出 pending 就绝不能删（INV-2d）。把"读不出"当成 0 是很自然的写法，
            // 也正是最危险的那个默认值 —— 它会在协议格式变化时，
            // 把硬拦截悄无声息地变成放行
            log.warn("consumer {} 的 pending 字段无法解析（{}），跳过清理",
                    name, fields.get(FIELD_PENDING));
            return false;
        }
        if (pending.getAsLong() > 0) {
            // 这不是"暂时不删"的性能取舍，是硬拦截。见类注释
            log.debug("consumer {} 还有 {} 条 pending，等回收捞走后再清理",
                    name, pending.getAsLong());
            return false;
        }

        OptionalLong idle = XInfo.number(fields.get(FIELD_IDLE));
        if (idle.isEmpty()) {
            log.warn("consumer {} 的 idle 字段无法解析，跳过清理", name);
            return false;
        }
        return Duration.ofMillis(idle.getAsLong()).compareTo(timings.consumerIdleThreshold()) > 0;
    }

    private Mono<Integer> delete(Map<String, Object> fields) {
        String name = XInfo.text(fields.get(FIELD_NAME));
        return runtime.commands()
                .xgroupDelconsumer(KeyNamespace.READY, Consumer.from(group, name))
                .doOnNext(discarded -> {
                    if (discarded != null && discarded > 0) {
                        // 走到这里说明 pending 在我们检查之后、删除之前变成了非 0。
                        // 那几条工作已经没了，且没有任何补救手段 —— 必须留下痕迹
                        log.error("清理 consumer {} 时连带销毁了 {} 条 pending —— "
                                + "这些工作已从消费组视角消失，无法再由回收捞回", name, discarded);
                    } else {
                        log.info("已清理死 consumer {}（idle > {} 且 pending = 0）",
                                name, timings.consumerIdleThreshold());
                    }
                })
                .thenReturn(1)
                .onErrorResume(error -> {
                    log.warn("删除 consumer {} 失败，下一周期重试", name, error);
                    return Mono.just(0);
                });
    }
}
