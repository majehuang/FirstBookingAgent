package io.agentharness.task.outbox;

import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.SessionRef;
import io.agentharness.store.message.MessageRepository;
import io.agentharness.store.message.PendingMessage;
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
     * @return 全部写完时完成；任一批失败则以错误结束，由调用方按 turn 失败策略处理
     */
    public Flux<ClientMessage> write(SessionRef session, Flux<PendingMessage> drafts) {
        return drafts
                .bufferTimeout(batchSize, window)
                .filter(batch -> !batch.isEmpty())
                .concatMap(batch -> persistThenPublish(session, batch));
    }

    /** 单批：合并 → 落库 → 推流。顺序不可颠倒。 */
    private Flux<ClientMessage> persistThenPublish(SessionRef session, List<PendingMessage> batch) {
        List<PendingMessage> merged = DeltaMerger.merge(batch);

        return Mono.fromCallable(() -> repository.append(session, merged))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(written -> Flux.fromIterable(written)
                        .concatMap(message -> outbox.publish(session, message)
                                .thenReturn(message)));
    }
}
