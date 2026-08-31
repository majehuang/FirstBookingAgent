package io.agentharness.task.outbox;

import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.SessionRef;
import io.agentharness.store.message.MessageRepository;
import io.agentharness.store.message.PendingMessage;
import io.agentharness.task.lease.WriteGate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

/**
 * 事件到用户可见消息的落地管道。
 *
 * <p>三条不变量在这一个方法里同时兑现：
 *
 * <ul>
 *   <li><b>INV-5 先落库、后 XADD。</b>反过来的话，落库失败时用户已经看到了消息，
 *       刷新之后它就消失了 —— 而这只在落库真的失败那一刻才暴露。</li>
 *   <li><b>INV-8 一律 {@code concatMap}。</b>换成 flatMap 时小批量看不出问题，
 *       批稍大就会出现批次乱序：第二批先落库、第一批后落库，序号与内容对不上。</li>
 *   <li><b>INV-7 阻塞调用 offload。</b>落库是阻塞 JDBC，跑在事件循环线程上会占死
 *       整个 pod 的所有 session。</li>
 * </ul>
 *
 * <p>合批窗口 80ms 约合 12 批/秒，仍然流畅；主要收益是信封开销摊薄与客户端渲染压力，
 * 不是 Redis QPS。
 */
public final class OutboxWriter {

    /** 达到条数即刻写出，不等窗口。 */
    public static final int DEFAULT_BATCH_SIZE = 64;
    public static final Duration DEFAULT_WINDOW = Duration.ofMillis(80);

    private final MessageRepository repository;
    private final OutboxStream outbox;
    private final int batchSize;
    private final Duration window;

    public OutboxWriter(MessageRepository repository, OutboxStream outbox) {
        this(repository, outbox, DEFAULT_BATCH_SIZE, DEFAULT_WINDOW);
    }

    public OutboxWriter(MessageRepository repository, OutboxStream outbox,
                        int batchSize, Duration window) {
        this.repository = repository;
        this.outbox = outbox;
        this.batchSize = batchSize;
        this.window = window;
    }

    /**
     * 消费一条消息草稿流，逐批落库并推流。
     *
     * <p>不带闸门的重载，只给<b>本来就不持牌</b>的路径用（单测、诊断）。
     * 生产路径必须走带 {@link WriteGate} 的那个。
     *
     * @return 全部写完时完成；任一批失败则以错误结束，由调用方按 turn 失败策略处理
     */
    public Flux<ClientMessage> write(SessionRef session, Flux<PendingMessage> drafts) {
        return write(session, drafts, WriteGate.open());
    }

    /**
     * 带执行权闸门的写入（LSE-008）。
     *
     * <p>闸门校验放在<b>每一批落库之前</b>，而不是整条流开头查一次。理由是合批窗口：
     * 上游被取消的那一刻，已经有一批草稿在 {@code bufferTimeout} 里攒着，
     * 取消信号不会让它们凭空消失 —— 它们仍会作为最后一批发出来。
     * 开头查一次的话，这最后一批会在执行权已经易主之后写进消息表和 outbox，
     * 混进新持有者的输出里。
     *
     * <h2>{@code onBackpressureBuffer} 不是可选项</h2>
     * {@code bufferTimeout} 是<b>定时推送</b>的：每 80ms 往下游推一批，<b>不看下游要不要</b>。
     * 而 {@code concatMap} 是<b>拉取</b>的：一次只要一个，等这一批写完才要下一批。
     * 两者直接相接时，只要一批的「落库 + XADD」超过 80ms，批次就会在中间堆起来，
     * 而 {@code bufferTimeout} 攒不下就直接抛
     * {@code OverflowException: Could not emit buffer due to lack of requests}
     * —— <b>整个 turn 当场失败</b>，用户看到的是一条 ERROR 回复。
     *
     * <p>一次 JDBC 插入加几次 {@code XADD} 超过 80ms 太容易了，所以这不是极端情况，
     * 是负载稍高就会撞上的常态。
     *
     * <p><b>这里只能缓冲，不能丢。</b>{@code onBackpressureDrop} 在别处（周期任务的节拍）
     * 是正确选择，在这里等于丢用户的消息 —— 直接违反 INV-5。
     * 缓冲的代价是内存，但它有天然上界：上游是模型的输出，本身受网络节奏限制，
     * 且一轮结束就释放。<b>用有界的内存换掉「用户的回复凭空变成一条报错」，没什么可犹豫的。</b>
     *
     * <p>真正的背压应该往上游传（模型生成得比落库快时慢下来），
     * 但 {@code bufferTimeout} 向上游请求的是无限，传不回去。要改成拉驱动的合批
     * 得换一套写法，那是另一件事。
     *
     * @see io.agentharness.task.schedule.Periodic 同一类坑的另外两处（{@code Flux.interval}）
     */
    public Flux<ClientMessage> write(SessionRef session, Flux<PendingMessage> drafts,
                                     WriteGate gate) {
        return drafts
                .bufferTimeout(batchSize, window)
                // 这一行不能删，理由见方法注释最后一段
                .onBackpressureBuffer()
                .filter(batch -> !batch.isEmpty())
                .concatMap(batch -> persistThenPublish(session, batch, gate));
    }

    /** 单批：校验 → 合并 → 落库 → 推流。顺序不可颠倒。 */
    private Flux<ClientMessage> persistThenPublish(SessionRef session, List<PendingMessage> batch,
                                                   WriteGate gate) {
        List<PendingMessage> merged = DeltaMerger.merge(batch);

        return gate.check(Mono.fromCallable(() -> repository.append(session, merged))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMapMany(written -> Flux.fromIterable(written)
                        .concatMap(message -> outbox.publish(session, message)
                                .thenReturn(message)));
    }
}
