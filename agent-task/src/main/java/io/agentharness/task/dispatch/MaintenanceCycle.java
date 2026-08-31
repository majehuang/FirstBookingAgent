package io.agentharness.task.dispatch;

import io.agentharness.task.schedule.Periodic;
import io.lettuce.core.StreamMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;

/**
 * 维护循环：<b>先回收，后清理</b>（INV-2b + INV-2d，CLN-004）。
 *
 * <p>这个类存在的全部理由就是那个顺序。两个任务分开调度、各跑各的周期，
 * 平时看不出问题 —— 直到某次清理恰好赶在回收之前跑，
 * 把一个刚死的 consumer 连同它名下还没被捞走的 pending 一起删掉。
 * 那不是概率事件，是<b>时间窗口问题</b>：pod 死后到回收生效之间的每一秒，
 * 都是这个错误顺序能造成损失的窗口。
 *
 * <p>把顺序编码进一个 {@code concatMap} 链，比在两个 {@code @Scheduled} 上写注释可靠。
 *
 * <h2>启动时先跑一次</h2>
 * 不等第一个 30 秒周期（RCV-001）。新 pod 起来的时刻，往往正是别的 pod 刚死的时刻
 * （滚动更新、OOM 重启），这时候 PEL 里最可能有活儿等着。
 * 等一个周期意味着把恢复时延白白加上 30 秒。
 *
 * <h2>回收失败时不清理</h2>
 * 回收异常中断意味着"PEL 里可能还有没捞完的工作"，而清理的顺序前提正是
 * "工作都已经捞走了"。前提不成立时<b>宁可不清理</b> —— 元数据多留一轮没有代价，
 * 删错了没法挽回。因此 {@link PendingReclaimer} 不自己吞异常，
 * 错误处理集中在这里一处。
 *
 * <p>但要说清楚：<b>这个顺序是活性优化，不是安全机制。</b>真正拦住 INV-2d 的是
 * {@link ConsumerJanitor} 自己的 {@code pending = 0} 检查 —— 回收没跑成时，
 * 那个死 consumer 的 pending 仍然大于 0，清理照样会拒绝删它。
 * 顺序保证的是"通常一个周期就能把死 consumer 收拾干净"，
 * 而不是"没有这个顺序就会丢工作"。把两者混为一谈，会让人以为删掉那个检查也没关系。
 */
public final class MaintenanceCycle {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceCycle.class);

    private final PendingReclaimer reclaimer;
    private final ConsumerJanitor janitor;
    private final InFlightSlots slots;
    private final TaskTimings timings;

    public MaintenanceCycle(PendingReclaimer reclaimer, ConsumerJanitor janitor,
                            InFlightSlots slots, TaskTimings timings) {
        this.reclaimer = reclaimer;
        this.janitor = janitor;
        this.slots = slots;
        this.timings = timings;
    }

    /**
     * 一轮维护：回收 → （成功才）清理。
     *
     * <p>回收到的令牌交给 {@code handler} 处理，走的是与正常认领<b>完全相同</b>的管道
     * （RCV-009）。这里不 {@code block} 也不等待处理完成 —— 维护循环的职责是把令牌
     * 交出去，令牌处理完要多久是 Worker 的事。
     *
     * @param handler 与 {@code XREADGROUP} 路径共用的令牌处理函数
     */
    public Mono<Void> runOnce(Function<StreamMessage<String, String>, Mono<Void>> handler) {
        return reclaimer.reclaim(slots.free())
                .collectList()
                .flatMap(claimed -> dispatchClaimed(claimed, handler)
                        // then 只在回收这一段正常结束后才走到清理。
                        // 回收出错时链路会短路，清理不执行
                        .then(janitor.sweep()))
                .doOnNext(cleaned -> {
                    if (cleaned > 0) {
                        log.info("本轮维护清理了 {} 个死 consumer", cleaned);
                    }
                })
                .onErrorResume(error -> {
                    log.error("维护循环本轮异常，跳过清理，下一周期重试", error);
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> dispatchClaimed(List<StreamMessage<String, String>> claimed,
                                       Function<StreamMessage<String, String>, Mono<Void>> handler) {
        if (claimed.isEmpty()) {
            return Mono.empty();
        }
        // 不等它们跑完 —— 等的话，一个长 turn 会把整个维护循环（包括清理与下一轮回收）
        // 挂在那里，而回收停跑正是 INV-2b 要防的那个静默故障
        Flux.fromIterable(claimed)
                .flatMap(handler, slots.capacity())
                .subscribe(ignored -> {
                }, error -> log.error("回收令牌的处理链异常终止", error));
        return Mono.empty();
    }

    /**
     * 启动跑一次，随后按周期跑（RCV-001 / RCV-002）。
     *
     * <p>周期用 {@code Flux.interval} 而不是"上一轮结束后再等 30 秒"：
     * 后者会让一次慢回收把后续所有周期整体往后推，
     * 而回收变慢的时候恰恰是最需要它按时跑的时候。
     */
    public Flux<Void> schedule(Function<StreamMessage<String, String>, Mono<Void>> handler) {
        return Flux.concat(
                runOnce(handler),
                // Periodic 而不是裸 Flux.interval：一次维护跑得比周期长时，
                // 裸 interval 会以 OverflowException 终止<b>整条订阅</b> ——
                // 回收就此永久停跑，而那正是 INV-2b 说的静默卡死。详见 Periodic 的注释
                Periodic.ticks(timings.reclaimInterval(),
                                skipped -> log.warn("维护循环上一轮尚未跑完，跳过本拍 {}。"
                                        + "持续出现说明回收周期设得太密或 Redis 变慢了", skipped))
                        // concatMap：两轮维护不能重叠。重叠时两轮回收会互相看到
                        // 对方刚认领的令牌，白白制造 lease 竞争
                        .concatMap(tick -> runOnce(handler)));
    }
}
