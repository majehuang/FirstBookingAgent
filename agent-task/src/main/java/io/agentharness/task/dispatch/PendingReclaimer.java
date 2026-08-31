package io.agentharness.task.dispatch;

import io.agentharness.keys.KeyNamespace;
import io.agentharness.redis.RedisRuntime;
import io.lettuce.core.Consumer;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.models.stream.ClaimedMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * PEL 回收（INV-2b）：把死掉的 pod 留下的令牌捞回来。
 *
 * <h2>这是整个 P3 里最容易漏掉、也最难被发现漏掉的一件事</h2>
 * <b>Redis 没有 PEL 自动回队机制。</b>官方文档的原话是
 * <i>leave the messages pending forever</i> —— 一个 consumer 取走令牌之后崩溃，
 * 那条记录就永远留在它名下。不会超时，不会重投，不会报错。
 *
 * <p>于是不跑回收的系统看起来完全正常：Redis 没有错误日志，ready 流长度正常，
 * 消费组存在，其他 session 一切照常。只有那几个倒霉 session 永久卡死，
 * 而用户能说出来的现象只有"这个对话卡住了"。
 * 唯一能看见它的是监控上的 PEL 深度与最老条目 idle（开发计划 §6）。
 *
 * <h2>三条不能改的细节</h2>
 * <ul>
 *   <li><b>{@code MIN-IDLE-TIME} 不能填 0</b> —— 0 会把正在被处理的令牌一起抢走。
 *       门槛由 {@link TaskTimings} 校验，那里有完整说明</li>
 *   <li><b>必须循环到游标 {@code 0-0}</b> —— 一次 {@code XAUTOCLAIM} 只扫一页。
 *       只处理第一页时，积压越深漏得越多，而少量积压时完全看不出来（RCV-006）</li>
 *   <li><b>任意 pod 都跑，不选主</b> —— 回收是幂等的：两个 pod 同时捞到同一个令牌，
 *       后续的 lease 竞争只会让一个真正执行。为此引入选主，等于为了省一次
 *       {@code SET NX} 而增加一个需要自己容错的分布式组件（RCV-008）</li>
 * </ul>
 *
 * <h2>回收来的令牌走的是同一条管道</h2>
 * {@code XAUTOCLAIM} 不带 {@code JUSTID} 时会连消息体一起返回，所以这里直接把它们
 * 交给和 {@code XREADGROUP} 完全相同的处理函数 —— 抢 lease、抽干、摘牌、交差（RCV-009）。
 * 不为回收令牌开特殊分支：一旦有第二条执行路径，两条路径的 lease 语义迟早会分叉。
 */
public final class PendingReclaimer {

    private static final Logger log = LoggerFactory.getLogger(PendingReclaimer.class);

    /** Stream 游标的终点。{@code XAUTOCLAIM} 返回它表示这一轮扫完了。 */
    private static final String CURSOR_END = "0-0";

    /** 游标起点。 */
    private static final String CURSOR_BEGIN = "0-0";

    /**
     * 单轮回收的页数上限。
     *
     * <p>纯粹是死循环的保险丝：正常情况下游标必然收敛到 {@code 0-0}。
     * 但如果某个版本的 Redis 在 deleted ID 的处理上返回了不推进的游标，
     * 没有这道上限就会把回收任务永久卡在这里 —— 而回收任务卡住的表现，
     * 恰好就是这个类要防的那个"静默永久滞留"。
     */
    private static final int MAX_PAGES = 1000;

    private final RedisRuntime runtime;
    private final String group;
    private final ConsumerName consumerName;
    private final TaskTimings timings;

    public PendingReclaimer(RedisRuntime runtime, String group, ConsumerName consumerName,
                            TaskTimings timings) {
        this.runtime = runtime;
        this.group = group;
        this.consumerName = consumerName;
        this.timings = timings;
    }

    /**
     * 扫一轮，把够老的 pending 令牌认领到自己名下并发出去。
     *
     * <p>认领的条数受空闲槽位限制（CAP-002）：满载时一条都不领。
     * 领回来放着等槽位是不行的 —— 见 {@link InFlightSlots} 的类注释。
     *
     * @param budget 本轮最多认领多少条，等于当前空闲槽位数
     * @return 认领到的令牌，按 Stream ID 顺序
     */
    public Flux<StreamMessage<String, String>> reclaim(int budget) {
        if (budget <= 0) {
            return Flux.empty();
        }
        return reclaimFrom(CURSOR_BEGIN, budget, MAX_PAGES);
    }

    /**
     * 一页一页往下扫。
     *
     * <p>用递归的 {@code expand} 而不是 {@code repeat}：下一次调用的起始游标来自
     * 上一次的返回值，这是个真正的串行依赖，用 {@code repeat} 表达不了。
     */
    private Flux<StreamMessage<String, String>> reclaimFrom(String cursor, int budget,
                                                            int pagesLeft) {
        if (budget <= 0 || pagesLeft <= 0) {
            if (pagesLeft <= 0) {
                log.warn("PEL 回收达到单轮页数上限 {}，本轮提前结束；下一周期继续", MAX_PAGES);
            }
            return Flux.empty();
        }

        return claimPage(cursor, budget).flatMapMany(page -> {
            var messages = page.getMessages();
            if (!messages.isEmpty()) {
                log.info("回收到 {} 个滞留令牌（idle > {}），交给本 pod 处理",
                        messages.size(), timings.reclaimMinIdle());
            }

            String next = page.getId();
            // 游标回到 0-0 表示扫完了。注意"这一页空"不等于"扫完了"——
            // 中间可能整页都是未达 MIN-IDLE 的条目，此时游标仍在推进
            if (next == null || CURSOR_END.equals(next)) {
                return Flux.fromIterable(messages);
            }
            return Flux.fromIterable(messages)
                    .concatWith(Flux.defer(() ->
                            reclaimFrom(next, budget - messages.size(), pagesLeft - 1)));
        });
    }

    private Mono<ClaimedMessages<String, String>> claimPage(String cursor, int budget) {
        XAutoClaimArgs<String> args = new XAutoClaimArgs<String>()
                .consumer(Consumer.from(group, consumerName.value()))
                .minIdleTime(timings.reclaimMinIdle())
                .startId(cursor)
                .count(budget);

        // 错误<b>不在这里吞</b>。吞掉的话 MaintenanceCycle 会以为回收正常结束、
        // 接着去跑清理，而"回收成功"恰恰是清理顺序的前提（CLN-004）。
        // 错误处理集中在 MaintenanceCycle 一处：它记录、跳过清理、下一周期重试（RCV-002）
        return runtime.commands().xautoclaim(KeyNamespace.READY, args);
    }
}
