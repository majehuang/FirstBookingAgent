package io.agentharness.task.schedule;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * 周期性任务的节拍源。
 *
 * <h2>为什么不能直接用 {@code Flux.interval(...).concatMap(work)}</h2>
 * 这个写法看起来完全正确，实际上藏着一个<b>会让整条循环永久死掉</b>的坑：
 *
 * <p>{@code Flux.interval} 不缓冲。只要 {@code work} 有一次跑得比周期长，
 * 下游就来不及 request，interval 立刻以
 * {@code OverflowException: Could not emit tick N due to lack of requests} <b>终止整个订阅</b>。
 * 不是跳过这一拍 —— 是<b>再也不会有下一拍</b>。
 *
 * <p><b>触发条件很具体</b>：下游必须是<b>一次只要一个</b>的操作符 ——
 * {@code concatMap} 正是（它 request(1)，等内层完成再要下一个）。
 * 而 {@code subscribe(consumer)} 或 {@code then()} 会 request 无限，
 * interval 永远不会溢出。所以
 * <b>「{@code interval} + {@code concatMap}」才是危险组合</b>，
 * 单独用 interval 打个节拍去触发无背压的动作是安全的。
 *
 * <p>后果按使用场景排序，一个比一个糟：
 * <ul>
 *   <li><b>回收循环死掉</b> —— {@code XAUTOCLAIM} 永久停跑，滞留令牌再也不会被捞回。
 *       这正是 INV-2b 描述的那个静默卡死，只不过成因是调度代码本身</li>
 *   <li><b>健康检查死掉</b> —— 此后永远不再告警，而"没有告警"会被读成"一切正常"</li>
 *   <li><b>续租心跳死掉</b> —— 牌子过期、turn 被中止</li>
 * </ul>
 *
 * <p>而且这三样死掉时都<b>没有任何业务报错</b>：Redis 正常、数据库正常、
 * 其它 session 照常处理。只有那些依赖周期任务的东西悄悄不动了。
 *
 * <h2>正确做法：跳过，而不是死掉</h2>
 * {@code onBackpressureDrop} 把"下游忙不过来"从<b>致命错误</b>变成<b>丢一拍</b>。
 * 对这三个场景来说丢一拍都是可接受的 —— 下一拍会补上；
 * 而永久停跑没有一个是可接受的。
 *
 * <p>丢拍<b>必须可见</b>：持续丢拍意味着周期设得太密或者依赖变慢了，
 * 那是需要人介入的信号，不该被悄悄吸收掉。
 *
 * <h2>这个坑项目里早就有人踩过</h2>
 * {@code RedisMessageSubscriber} 的注释里写着同一件事：
 * <i>"轮询用 repeatWhen + delayElements 而不是 Flux.interval：
 * 后者按固定节拍发信号，一次读取慢于间隔时信号会堆积，
 * 而 interval 默认的背压策略是报错 —— 表现为高负载下连接莫名断开。"</i>
 * 那是 P1 在读取侧得到的教训，P3 在回收、健康检查、续租三处又踩了一遍。
 * 把它收进一个有名字的地方，比指望下一个人记得那段注释可靠。
 */
public final class Periodic {

    private Periodic() {
    }

    /**
     * 固定周期的节拍，上一拍没跑完时<b>丢弃</b>新节拍而不是让整条流报错终止。
     *
     * @param period    周期
     * @param onSkipped 丢拍时的回调，用来计数或告警
     */
    public static Flux<Long> ticks(Duration period, Consumer<Long> onSkipped) {
        return Flux.interval(period, period).onBackpressureDrop(onSkipped);
    }
}
