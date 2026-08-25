package io.agentharness.tui.loopback;

import io.agentharness.protocol.Ack;
import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.ControlFrame;
import io.agentharness.protocol.InstructionKind;
import io.agentharness.protocol.MessageRole;
import io.agentharness.protocol.MessageType;
import io.agentharness.protocol.SessionRef;
import io.agentharness.protocol.TurnPhase;
import io.agentharness.protocol.UserInstruction;
import io.agentharness.tui.port.AgentBackend;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进程内假后端：把 {@link ScriptedReply} 的步骤按时序翻译成协议消息。
 *
 * <p>它同时**模拟了两条 SSE 的语义**，这样 TUI 主流程不需要为"真后端 / 假后端"分叉：
 * <ul>
 *   <li>消息流用 {@code replay().limit(5min)} —— 对应 outbox 的 5–10 分钟保留窗口，
 *       新订阅者拿到窗口内全部消息，正是"建连全量重放"</li>
 *   <li>控制流用 {@code replay().latest()} —— 对应"先下发完整快照，再接后续帧"</li>
 * </ul>
 *
 * <p>事件管道一律 {@code concatMap}（INV-8）：换成 flatMap 时小批量看不出问题，
 * 批稍大就会出现 delta 错位。这里是这条不变量在本轮唯一的落地点。
 */
public final class LoopbackBackend implements AgentBackend {

    private static final Duration DEFAULT_TOKEN_DELAY = Duration.ofMillis(28);
    private static final Duration DEFAULT_TOOL_DELAY = Duration.ofMillis(700);
    private static final Duration OUTBOX_WINDOW = Duration.ofMinutes(5);
    private static final int CHUNK_SIZE = 2;

    private final Sinks.Many<ClientMessage> messages = Sinks.many().replay().limit(OUTBOX_WINDOW);
    private final Sinks.Many<ControlFrame> control = Sinks.many().replay().latest();
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong replyCounter = new AtomicLong();
    private final AtomicLong blockCounter = new AtomicLong();
    private final AtomicReference<Disposable> activeTurn = new AtomicReference<>();
    private final AtomicReference<ControlFrame> currentControl = new AtomicReference<>(ControlFrame.idle());

    private final Duration tokenDelay;
    private final Duration toolDelay;

    public LoopbackBackend() {
        this(DEFAULT_TOKEN_DELAY, DEFAULT_TOOL_DELAY);
    }

    public LoopbackBackend(Duration tokenDelay, Duration toolDelay) {
        this.tokenDelay = tokenDelay;
        this.toolDelay = toolDelay;
        control.tryEmitNext(currentControl.get());
    }

    @Override
    public String name() {
        return "loopback";
    }

    @Override
    public Flux<ClientMessage> messages(SessionRef session) {
        return messages.asFlux();
    }

    @Override
    public Flux<ControlFrame> control(SessionRef session) {
        return control.asFlux();
    }

    @Override
    public Mono<Ack> send(SessionRef session, UserInstruction instruction) {
        return Mono.fromSupplier(() -> instruction.kind() == InstructionKind.CONTROL
                ? cancel(instruction)
                : startTurn(instruction));
    }

    @Override
    public void close() {
        Disposable running = activeTurn.getAndSet(null);
        if (running != null) {
            running.dispose();
        }
        messages.tryEmitComplete();
        control.tryEmitComplete();
    }

    private Ack startTurn(UserInstruction instruction) {
        Disposable previous = activeTurn.get();
        if (previous != null && !previous.isDisposed()) {
            // 上一轮还没结束，真实系统里这条会排队等游标推进；这里直接拒绝更直观
            throw new IllegalStateException("上一轮回复尚未结束");
        }

        String replyId = "r-" + replyCounter.incrementAndGet();

        // 与真后端保持同一套语义：用户消息也进流，客户端收到推送后才回显
        push(ClientMessage.userText(nextSeq(), replyId,
                "u-" + instruction.instructionId(), instruction.text(), Instant.now()));

        publishControl(currentControl.get().withTurnStarted(replyId).withPhase(TurnPhase.THINKING));

        List<ReplyStep> steps = ScriptedReply.forPrompt(instruction.text());
        Disposable subscription = Flux.fromIterable(steps)
                .concatMap(step -> emit(replyId, step))
                .doOnComplete(() -> finishTurn(replyId))
                .subscribe(ignored -> {
                }, error -> pushError(replyId, error));
        activeTurn.set(subscription);

        // 真实实现里用户消息本身也会落库并进 outbox（多端同步需要），
        // 那时 Ack.msgSeq 是用户消息那一行的序号。假后端不产生这一行，因此回当前水位。
        return new Ack(instruction.instructionId(), replyId, seq.get());
    }

    private Ack cancel(UserInstruction instruction) {
        String targetReplyId = instruction.targetReplyId();
        ControlFrame snapshot = currentControl.get();

        if (!snapshot.turnActive() || !targetReplyId.equals(snapshot.activeReplyId())) {
            // targetReplyId 作用域校验：目标 turn 已不存在则丢弃，绝不误杀新 turn
            return new Ack(instruction.instructionId(), targetReplyId, seq.get());
        }

        publishControl(snapshot.withStopping());
        Disposable running = activeTurn.getAndSet(null);
        if (running != null) {
            running.dispose();
        }
        push(ClientMessage.system(nextSeq(), targetReplyId, "已停止，上面已生成的部分保留", Instant.now()));
        publishControl(currentControl.get().withTurnEnded(TurnPhase.DONE));
        return new Ack(instruction.instructionId(), targetReplyId, seq.get());
    }

    private void finishTurn(String replyId) {
        activeTurn.set(null);
        ControlFrame snapshot = currentControl.get();
        if (replyId.equals(snapshot.activeReplyId())) {
            publishControl(snapshot.withTurnEnded(TurnPhase.DONE));
        }
    }

    private Flux<ClientMessage> emit(String replyId, ReplyStep step) {
        return switch (step) {
            case ReplyStep.Text text -> emitText(replyId, text.content());
            case ReplyStep.ToolCall call -> emitToolCall(replyId, call);
            case ReplyStep.ToolResult result -> Flux.just(push(new ClientMessage(nextSeq(), replyId,
                    nextBlockId(), MessageRole.ASSISTANT, MessageType.TOOL_RESULT,
                    result.summary(), Map.of(), Instant.now())));
            case ReplyStep.Card card -> emitCard(replyId, card);
            case ReplyStep.Failure failure -> emitFailure(replyId, failure);
        };
    }

    private Flux<ClientMessage> emitText(String replyId, String content) {
        String blockId = nextBlockId();
        List<String> chunks = chunk(content, CHUNK_SIZE);

        return Flux.defer(() -> {
                    publishControl(currentControl.get().withPhase(TurnPhase.WRITING));
                    return Flux.fromIterable(chunks);
                })
                .concatMap(piece -> Mono.delay(tokenDelay).thenReturn(piece))
                .map(piece -> push(ClientMessage.textDelta(nextSeq(), replyId, blockId, piece, Instant.now())))
                .concatWith(Mono.fromSupplier(
                        () -> push(ClientMessage.textEnd(nextSeq(), replyId, blockId, Instant.now()))));
    }

    private Flux<ClientMessage> emitToolCall(String replyId, ReplyStep.ToolCall call) {
        return Flux.defer(() -> {
                    publishControl(currentControl.get().withPhase(TurnPhase.CALLING_TOOL));
                    return Flux.just(push(new ClientMessage(nextSeq(), replyId, nextBlockId(),
                            MessageRole.ASSISTANT, MessageType.TOOL_CALL, call.tool(),
                            Map.of("tool", call.tool(), "args", call.args()), Instant.now())));
                })
                .concatWith(Mono.delay(toolDelay).thenMany(Flux.empty()));
    }

    private Flux<ClientMessage> emitCard(String replyId, ReplyStep.Card card) {
        Map<String, Object> payload = Map.of(
                "title", card.title(),
                "items", card.items(),
                "dataAsOf", card.dataAsOf());
        return Flux.just(push(new ClientMessage(nextSeq(), replyId, nextBlockId(),
                MessageRole.ASSISTANT, MessageType.CARD, card.title(), payload, Instant.now())));
    }

    private Flux<ClientMessage> emitFailure(String replyId, ReplyStep.Failure failure) {
        return Flux.defer(() -> {
            ClientMessage message = push(ClientMessage.error(nextSeq(), replyId, failure.reason(), Instant.now()));
            publishControl(currentControl.get().withTurnEnded(TurnPhase.FAILED));
            return Flux.just(message);
        });
    }

    private void pushError(String replyId, Throwable error) {
        push(ClientMessage.error(nextSeq(), replyId,
                "本地引擎异常：" + error.getMessage(), Instant.now()));
        publishControl(currentControl.get().withTurnEnded(TurnPhase.FAILED));
        activeTurn.set(null);
    }

    private ClientMessage push(ClientMessage message) {
        messages.tryEmitNext(message);
        return message;
    }

    private void publishControl(ControlFrame frame) {
        ControlFrame stamped = frame.withCtrlId(String.valueOf(seq.get()));
        currentControl.set(stamped);
        control.tryEmitNext(stamped);
    }

    private long nextSeq() {
        return seq.incrementAndGet();
    }

    private String nextBlockId() {
        return "b-" + blockCounter.incrementAndGet();
    }

    /** 把整段文本切成小块，模拟逐 token 吐字。换行必须落在块边界之外，交给行缓冲处理。 */
    static List<String> chunk(String text, int size) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            chunks.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return List.copyOf(chunks);
    }
}
