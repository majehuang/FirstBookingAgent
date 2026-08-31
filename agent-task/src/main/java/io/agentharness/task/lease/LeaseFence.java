package io.agentharness.task.lease;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 执行权的闸门（P3-10 / LSE-007～LSE-009）。
 *
 * <p>续租失败意味着牌子已经不在我们手上 —— 可能过期了，也可能已经被别的 pod 抢走。
 * 从这一刻起，<b>这个进程对该 session 的任何写入都是非法的</b>：
 * 消息表、outbox、游标、ACK，一个都不能再动。
 *
 * <h2>为什么需要"闸门"这个东西，而不是抛个异常了事</h2>
 * 一个 turn 里同时挂着好几条独立的流：引擎在吐事件，OutboxWriter 在按 80ms 合批，
 * 冷存储在旁路写。异常只能沿着<b>一条</b>链传播，传不到另外几条上。
 * 而这里需要的是"所有正在飞的写入立刻一起停"，那必须有一个共享的开关。
 *
 * <h2>两道防线，缺一不可</h2>
 * <ul>
 *   <li><b>取消</b>（{@link #fence}）—— 用 {@code takeUntilOther} 把上游整条流掐掉。
 *       这是快的那道，模型不会继续生成，不浪费配额</li>
 *   <li><b>校验</b>（{@link #check}）—— 每次真正落库/写流之前再问一次。
 *       这是稳的那道：取消信号的传播不是瞬时的，
 *       已经在管道里飞着的那一批仍可能抵达写入点</li>
 * </ul>
 * 只有取消而没有校验，就会漏掉"信号发出时已在途"的那一批 —— 而 80ms 的合批窗口
 * 意味着在途的从来不是一两条。只有校验而没有取消，模型会一直生成到自然结束，
 * 中止就不是"立即"了。
 */
public final class LeaseFence implements WriteGate {

    private static final Logger log = LoggerFactory.getLogger(LeaseFence.class);

    private final String sessionId;
    private final AtomicBoolean lost = new AtomicBoolean();
    private final AtomicReference<String> reason = new AtomicReference<>();

    /**
     * 闸门落下的信号。
     *
     * <p>用 {@code Sinks.empty()} 的多播语义：一个 turn 里有多条流要监听同一个信号，
     * 单播的 sink 只有第一个订阅者能收到，其余的会一直等下去。
     */
    private final Sinks.Empty<Void> tripped = Sinks.empty();

    public LeaseFence(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * 落闸。幂等 —— 续租任务与业务链路可能同时发现，只处理第一次。
     */
    public void trip(String why) {
        if (lost.compareAndSet(false, true)) {
            reason.set(why);
            log.warn("session {} 执行权已失去：{} —— 立即中止本轮，停止一切写入", sessionId, why);
            tripped.tryEmitEmpty();
        }
    }

    public boolean isLost() {
        return lost.get();
    }

    /** 供 {@code takeUntilOther} 使用的中止信号。 */
    public Mono<Void> tripped() {
        return tripped.asMono();
    }

    /**
     * 给一条流装上闸门：闸门一落，上游立刻被取消。
     *
     * <p>{@code takeUntilOther} 是<b>取消</b>而不是<b>报错</b>：下游看到的是正常完成。
     * 这是刻意的 —— 报错会走进 turn 的失败分支，而失败分支要写一条 ERROR 消息，
     * 那正是这个类要禁止的事。中止后的收尾由 {@link #check} 在写入点拦下。
     */
    public <T> Flux<T> fence(Flux<T> source) {
        return source.takeUntilOther(tripped.asMono());
    }

    /**
     * 写入前的校验。返回的 Mono 在闸门已落时直接以 {@link LeaseLostException} 结束。
     *
     * <p>用法是包住每一个真正产生副作用的步骤，而不是在 turn 开头查一次 ——
     * turn 可能跑几十秒，开头那次检查在结尾早已过期。
     */
    @Override
    public <T> Mono<T> check(Mono<T> work) {
        return Mono.defer(() -> isLost()
                ? Mono.error(new LeaseLostException(
                        "session " + sessionId + " 的执行权已失去（" + reason.get() + "），拒绝写入"))
                : work);
    }

    /** 诊断用。 */
    public String reason() {
        return reason.get();
    }
}
