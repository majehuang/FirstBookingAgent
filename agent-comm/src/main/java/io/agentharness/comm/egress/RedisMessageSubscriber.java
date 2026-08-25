package io.agentharness.comm.egress;

import io.agentharness.keys.KeyNamespace;
import io.agentharness.protocol.ClientCapabilities;
import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.ControlFrame;
import io.agentharness.protocol.Json;
import io.agentharness.protocol.SessionRef;
import io.agentharness.redis.Cursors;
import io.agentharness.redis.RedisRuntime;
import io.agentharness.redis.StreamPayload;
import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 两条出站流的 Redis 实现。
 *
 * <p><b>只用非阻塞轮询，绝不用 {@code XREAD BLOCK}（INV-12）。</b>
 * 阻塞命令必须独占连接，Redis 连接数会随 SSE 连接数线性放大，几千即打满。
 * 非阻塞命令由 Lettuce 多路复用，上千个订阅共享一条连接。
 *
 * <p>轮询用 {@code repeatWhen + delayElements} 而不是 {@code Flux.interval}：
 * 后者按固定节拍发信号，一次读取慢于间隔时信号会堆积，
 * 而 interval 默认的背压策略是报错 —— 表现为高负载下连接莫名断开。
 * 前者是"读完 → 等一拍 → 再读"，天然不会重叠。
 *
 * <p><b>不使用消费组。</b>outbox 是扇出语义：同一 session 的两个客户端各自要看到完整消息流。
 * 用了消费组之后多端登录的两个设备会各拿一半事件（开发规划 G 节）。
 *
 * <p><b>能力降级在读取侧做，不在写入侧。</b>outbox 里必须存完整消息 ——
 * 写入时就削掉的话，同一个 session 上能力不同的两个客户端只能看同一份被削过的内容，
 * 而且新客户端升级之后也拿不回历史里的富消息。
 */
public final class RedisMessageSubscriber implements MessageSubscriber {

    private final RedisRuntime runtime;
    private final ClientCapabilities capabilities;

    /** 默认按全能力下发。生产上应当由客户端建连时上报的能力决定。 */
    public RedisMessageSubscriber(RedisRuntime runtime) {
        this(runtime, ClientCapabilities.full());
    }

    public RedisMessageSubscriber(RedisRuntime runtime, ClientCapabilities capabilities) {
        this.runtime = runtime;
        this.capabilities = capabilities == null ? ClientCapabilities.full() : capabilities;
    }

    @Override
    public Flux<ClientMessage> messages(SessionRef session) {
        return replayThenFollow(
                KeyNamespace.outbox(session.sessionId()),
                Cursors.BEGINNING,
                runtime.messagePollInterval(),
                entry -> StreamPayload.read(entry.getBody(), ClientMessage.class))
                // 出站唯一的咽喉：客户端不支持的类型在这里压成纯文本。
                // 降级放在读取侧而不是写入侧 —— outbox 里存的必须是完整消息，
                // 否则两个能力不同的客户端就只能看同一份被削过的内容
                .map(capabilities::degrade);
    }

    @Override
    public Flux<ControlFrame> control(SessionRef session) {
        String stateKey = KeyNamespace.state(session.sessionId());
        String streamKey = KeyNamespace.ctrlStream(session.sessionId());

        return Mono.defer(() -> runtime.commands().get(stateKey))
                .map(json -> Json.read(json, ControlFrame.class))
                .defaultIfEmpty(ControlFrame.idle())
                .flatMapMany(snapshot -> {
                    // 快照自带水位，从它之后重放 —— 既不漏也不重（INV-11）。
                    // 水位为空说明这个 session 还没有过控制帧，从头读
                    String watermark = snapshot.ctrlId() == null
                            ? Cursors.BEGINNING : snapshot.ctrlId();
                    return Flux.just(snapshot).concatWith(
                            follow(streamKey, watermark, runtime.controlPollInterval(),
                                    RedisMessageSubscriber::decodeControlFrame));
                });
    }

    /**
     * 建连全量重放 + 转入实时跟随。
     *
     * <p>{@code Flux.defer} 让<b>每个订阅者拥有自己的游标</b>：
     * 共享游标的话，第二个客户端建连时会从第一个客户端读到的位置开始，
     * 于是它永远看不到那之前的消息（SSE-008 就是在测这件事）。
     */
    private <T> Flux<T> replayThenFollow(String key, String from, Duration interval,
                                         Function<StreamMessage<String, String>, T> decode) {
        return Flux.defer(() -> {
            AtomicReference<String> cursor = new AtomicReference<>(from);
            Flux<T> replay = runtime.commands()
                    .xrange(key, Range.unbounded())
                    .doOnNext(entry -> cursor.set(entry.getId()))
                    .map(decode);
            return replay.concatWith(followFrom(key, cursor, interval, decode));
        });
    }

    private <T> Flux<T> follow(String key, String from, Duration interval,
                               Function<StreamMessage<String, String>, T> decode) {
        return Flux.defer(() -> followFrom(key, new AtomicReference<>(from), interval, decode));
    }

    private <T> Flux<T> followFrom(String key, AtomicReference<String> cursor, Duration interval,
                                   Function<StreamMessage<String, String>, T> decode) {
        return Flux.defer(() -> runtime.commands()
                        .xread(XReadArgs.Builder.count(runtime.config().readBatchSize()),
                                XReadArgs.StreamOffset.from(key, cursor.get())))
                .doOnNext(entry -> cursor.set(entry.getId()))
                .map(decode)
                .repeatWhen(completed -> completed.delayElements(interval));
    }

    /**
     * 控制帧的 {@code ctrlId} 取自流条目的 ID 本身。
     *
     * <p>写入侧的 Lua 把水位塞进 state 快照，但流里那一帧不带它 ——
     * 因为条目 ID 就是水位，重复存一份只会多一个可能不一致的来源。
     */
    private static ControlFrame decodeControlFrame(StreamMessage<String, String> entry) {
        return StreamPayload.read(entry.getBody(), ControlFrame.class).withCtrlId(entry.getId());
    }
}
