package io.agentharness.tui;

import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.ControlFrame;
import io.agentharness.protocol.SessionRef;
import io.agentharness.protocol.UserInstruction;
import io.agentharness.tui.input.CommandOutcome;
import io.agentharness.tui.input.InputAction;
import io.agentharness.tui.input.InputParser;
import io.agentharness.tui.input.SlashCommandHandler;
import io.agentharness.tui.port.AgentBackend;
import io.agentharness.tui.port.HistorySource;
import io.agentharness.tui.render.Banner;
import io.agentharness.tui.render.LineKind;
import io.agentharness.tui.render.RenderedLine;
import io.agentharness.tui.render.StatusLine;
import io.agentharness.tui.render.Transcript;
import io.agentharness.tui.state.ConnectionState;
import io.agentharness.tui.state.SeqRule;
import io.agentharness.tui.state.SeqVerdict;
import io.agentharness.tui.state.UiEvent;
import io.agentharness.tui.state.UiState;
import io.agentharness.tui.state.UiStateReducer;
import io.agentharness.tui.terminal.ReadResult;
import io.agentharness.tui.terminal.TerminalUi;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TUI 主循环：读一行、解析、投递；同时把后端两条流渲染到滚动区与状态行。
 *
 * <p>线程模型只有两条：
 * <ul>
 *   <li><b>主线程</b>阻塞在 {@code readLine} 上，负责解析输入与发起投递</li>
 *   <li><b>单线程 render scheduler</b> 独占所有终端写入 ——
 *       消息流、控制流、状态定时刷新全部 {@code publishOn} 到它上面</li>
 * </ul>
 * 终端写入只有一个生产者，是这里唯一需要守住的规则；违反了就会看到光标乱跳和串行。
 */
public final class TuiApp implements AutoCloseable {

    private static final Duration TURN_WAIT_TIMEOUT = Duration.ofSeconds(120);
    private static final int HISTORY_PAGE_SIZE = 500;
    private static final Duration CATCH_UP_TIMEOUT = Duration.ofSeconds(10);

    private final TerminalUi ui;
    private final AgentBackend backend;
    private final TuiConfig config;
    private final Scheduler renderScheduler;

    private final AtomicReference<UiState> state;
    private final AtomicReference<Transcript> transcript = new AtomicReference<>(Transcript.empty());
    private final AtomicReference<Disposable> streams = new AtomicReference<>(Disposables.none());
    private final AtomicReference<TurnGate> turnGate = new AtomicReference<>();
    private final SlashCommandHandler commands = new SlashCommandHandler(
            SlashCommandHandler.randomSessionIds());

    public TuiApp(TerminalUi ui, AgentBackend backend, TuiConfig config) {
        this.ui = ui;
        this.backend = backend;
        this.config = config;
        this.renderScheduler = Schedulers.newSingle("tui-render", true);
        this.state = new AtomicReference<>(UiState.initial(config.session(), backend.name()));
    }

    /** 跑到用户退出为止。返回进程退出码。 */
    public int run() {
        ui.printLines(Banner.welcome(config.session(), backend.name()));
        subscribeStreams(config.session());

        while (true) {
            ReadResult result = ui.readLine(prompt());
            boolean quit = switch (result) {
                case ReadResult.Line line -> handleLine(line.text());
                case ReadResult.Interrupted ignored -> {
                    handleInterrupt();
                    yield false;
                }
                case ReadResult.EndOfInput ignored -> true;
            };
            if (quit) {
                return 0;
            }
        }
    }

    @Override
    public void close() {
        streams.get().dispose();
        renderScheduler.dispose();
        backend.close();
        ui.close();
    }

    // ---------- 输入处理 ----------

    private boolean handleLine(String raw) {
        InputAction action = InputParser.parse(raw);
        return switch (action) {
            case InputAction.Nothing ignored -> false;
            case InputAction.SendMessage message -> {
                sendMessage(message.text());
                yield false;
            }
            case InputAction.UnknownCommand unknown -> {
                ui.printLines(List.of(RenderedLine.hint("未知命令 " + unknown.raw() + "，输入 /help 看可用命令")));
                yield false;
            }
            case InputAction.RunCommand command -> runCommand(command);
        };
    }

    /**
     * 执行一条斜杠命令。
     *
     * <p>判断逻辑全在 {@link SlashCommandHandler} 里（纯函数、有测试），
     * 这里只负责把结果落到终端上。
     *
     * @return 是否退出
     */
    private boolean runCommand(InputAction.RunCommand command) {
        CommandOutcome outcome = commands.handle(command, state.get(), Instant.now());
        return switch (outcome) {
            case CommandOutcome.Print print -> {
                ui.printLines(print.lines());
                yield false;
            }
            case CommandOutcome.SwitchSession switchSession -> {
                switchSession(switchSession.sessionId());
                yield false;
            }
            case CommandOutcome.Interrupt ignored -> {
                handleInterrupt();
                yield false;
            }
            case CommandOutcome.ClearScreen ignored -> {
                ui.clearScreen();
                yield false;
            }
            case CommandOutcome.Nothing ignored -> false;
            case CommandOutcome.Quit ignored -> true;
        };
    }

    /**
     * 投递一条用户消息。
     *
     * <p><b>刻意不本地回显。</b>用户自己的话也由服务端落库、经流推回来之后才渲染 ——
     * 这样一个会话里只有一套顺序来源（msgSeq），多端登录时各端看到的顺序必然一致，
     * 空窗判定也不会被本地插入的行打乱。
     * 代价是多一个来回的感知延迟，用状态行的"投递中"补偿。
     */
    private void sendMessage(String text) {
        UiState snapshot = state.get();

        if (!snapshot.inputAllowed()) {
            ui.printLines(List.of(RenderedLine.hint(
                    "当前不接受输入（" + snapshot.phase().label() + "），^C 可以停止本轮")));
            return;
        }

        state.updateAndGet(current -> current.withPendingInput(text));
        refreshStatus();

        boolean waitForTurn = !ui.interactive();
        if (waitForTurn) {
            turnGate.set(new TurnGate());
        }

        UserInstruction instruction = UserInstruction.message(newInstructionId(), text, Instant.now());
        backend.send(snapshot.session(), instruction)
                .subscribeOn(Schedulers.boundedElastic())
                .publishOn(renderScheduler)
                .subscribe(ack -> {
                }, error -> {
                    openGate();
                    // 投递失败时用户的话不会出现在任何地方，所以必须把原文回显出来，
                    // 否则他既看不到自己说了什么，也不知道要不要重发
                    state.updateAndGet(current -> current.withPendingInput(null));
                    ui.printLines(List.of(
                            RenderedLine.of(LineKind.USER, "› " + text),
                            RenderedLine.of(LineKind.ERROR, "✗ 投递失败：" + rootMessage(error)),
                            RenderedLine.hint("未收到回执时应当带同一个 instructionId 重试（INV-1）")));
                    refreshStatus();
                });

        if (waitForTurn) {
            awaitTurnEnd();
            catchUpFromHistory();
        }
    }

    /**
     * 从消息表对账，补上还没轮询到的消息。
     *
     * <p>控制帧说 turn 结束时，最后几条消息可能还在 outbox 的轮询间隔里 ——
     * 逐行模式紧接着就会读下一行、可能直接退出，那些消息就再也不会被渲染。
     *
     * <p>补的办法不是加延时（延时多少都是猜），而是<b>从真相源对账</b>：
     * Worker 先落库后 XADD，所以消息表里有的一定不少于 outbox。
     * 重复的部分由序号规则丢弃，不会重复渲染。
     */
    private void catchUpFromHistory() {
        backend.history().ifPresent(history -> Mono.fromRunnable(() -> {
                    UiState snapshot = state.get();
                    try {
                        history.since(snapshot.session(), snapshot.lastMsgSeq(), HISTORY_PAGE_SIZE)
                                .forEach(this::onMessage);
                    } catch (RuntimeException e) {
                        ui.printLines(List.of(RenderedLine.hint("对账失败：" + rootMessage(e))));
                    }
                })
                // 渲染必须在 render 线程上做：主线程直接调会与流的推送并发改同一份文字稿
                .subscribeOn(renderScheduler)
                .block(CATCH_UP_TIMEOUT));
    }

    /** 非交互模式下等这一轮结束。超时只提示，不中止会话 —— 卡住的原因需要用户自己看。 */
    private void awaitTurnEnd() {
        TurnGate gate = turnGate.get();
        if (gate == null) {
            return;
        }
        try {
            if (!gate.await(TURN_WAIT_TIMEOUT)) {
                ui.printLines(List.of(RenderedLine.hint(
                        "等待超时（" + TURN_WAIT_TIMEOUT.toSeconds() + "s），本轮可能仍在进行")));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            turnGate.set(null);
        }
    }

    private void openGate() {
        TurnGate gate = turnGate.get();
        if (gate != null) {
            gate.forceOpen();
        }
    }

    private void handleInterrupt() {
        UiState snapshot = state.get();
        ControlFrame control = snapshot.control();

        if (!control.turnActive() || control.activeReplyId() == null) {
            ui.printLines(List.of(RenderedLine.hint("当前空闲。^D 退出。")));
            return;
        }

        UserInstruction cancel = UserInstruction.cancel(
                newInstructionId(), control.activeReplyId(), Instant.now());

        // 状态条上挂出"已发出"，直到控制帧认下它为止。
        // 控制指令不会以消息形式回到流里，没有这个标记的话中间完全没有反馈，
        // 用户会以为没生效而反复按 —— 于是 inbox 里堆一串取消指令
        state.updateAndGet(current -> current.withPendingControl("/stop"));

        backend.send(snapshot.session(), cancel)
                .subscribeOn(Schedulers.boundedElastic())
                .publishOn(renderScheduler)
                .subscribe(ack -> {
                }, error -> {
                    // 投递失败就把标记撤掉，否则它会一直挂着，
                    // 显示成"已发出"而实际上根本没出去
                    state.updateAndGet(current -> current.withPendingControl(null));
                    ui.printLines(List.of(
                            RenderedLine.of(LineKind.ERROR, "✗ 停止指令投递失败：" + rootMessage(error))));
                });
    }

    private void switchSession(String sessionId) {
        SessionRef next = SessionRef.of(config.session().userId(), sessionId);
        streams.get().dispose();
        transcript.set(Transcript.empty());
        state.set(UiState.initial(next, backend.name()));
        ui.printLines(List.of(RenderedLine.of(LineKind.SYSTEM, "· 切换到会话 " + next)));
        subscribeStreams(next);
    }

    // ---------- 后端流 ----------

    private void subscribeStreams(SessionRef session) {
        updateState(new UiEvent.ConnectionChanged(ConnectionState.CONNECTED, Instant.now()));

        // 先拉历史再订阅流：把本地水位抬到真实位置，
        // 随后 outbox 窗口重放过来的旧消息会被序号规则判为重复而丢弃（SeqVerdict.DISCARD）。
        // 顺序颠倒的话，历史会打在实时消息之后，看起来就像消息乱序了。
        backend.history().ifPresent(history -> rebuildFromHistory(history, session, 0));

        Disposable messages = backend.messages(session)
                .publishOn(renderScheduler)
                .concatMap(message -> Flux.just(message).doOnNext(this::onMessage))
                .subscribe(ignored -> {
                }, this::onStreamError);

        Disposable control = backend.control(session)
                .publishOn(renderScheduler)
                .subscribe(this::onControl, this::onStreamError);

        Disposable ticker = Flux.interval(config.statusTick(), renderScheduler)
                .subscribe(tick -> refreshStatus());

        streams.set(Disposables.composite(messages, control, ticker));
        refreshStatus();
    }

    private void onMessage(ClientMessage message) {
        UiState before = state.get();
        SeqVerdict verdict = SeqRule.judge(before.lastMsgSeq(), message.msgSeq());

        if (verdict == SeqVerdict.DISCARD) {
            return;
        }
        if (verdict == SeqVerdict.GAP) {
            onGap(before);
        }

        updateState(new UiEvent.MessageArrived(message, Instant.now()));

        Transcript.Emission emission = transcript.get().accept(message);
        transcript.set(emission.transcript());
        ui.printLines(emission.lines());
        ui.setLiveTail(emission.liveTail());
        refreshStatus();
    }

    /**
     * 空窗恢复：清空缓冲 → 拉取历史 → 重建 → 再接后续帧。
     *
     * <p>"清空"后面必须跟着"拉取"。只清空会让用户看到空白页 ——
     * 这是这条路径上最容易漏掉的半步。
     */
    private void onGap(UiState before) {
        Transcript.Emission cleared = transcript.get().clear();
        transcript.set(cleared.transcript());
        ui.setLiveTail("");

        long cursor = SeqRule.historyCursorAfterGap(before.lastMsgSeq());
        Optional<HistorySource> history = backend.history();
        if (history.isEmpty()) {
            ui.printLines(List.of(RenderedLine.hint(
                    "检测到空窗（本地 seq " + cursor + "）：当前后端没有消息表，补不回来")));
            return;
        }
        rebuildFromHistory(history.get(), before.session(), cursor);
    }

    private void rebuildFromHistory(HistorySource history, SessionRef session, long cursor) {
        try {
            List<ClientMessage> past = history.since(session, cursor, HISTORY_PAGE_SIZE);
            if (past.isEmpty()) {
                return;
            }
            Transcript replay = Transcript.empty();
            List<RenderedLine> lines = new ArrayList<>();
            for (ClientMessage message : past) {
                Transcript.Emission emission = replay.accept(message);
                replay = emission.transcript();
                lines.addAll(emission.lines());
            }
            lines.addAll(replay.flush().lines());

            transcript.set(Transcript.empty());
            ui.printLines(lines);
            state.updateAndGet(current -> current.withMsgSeq(past.get(past.size() - 1).msgSeq()));
        } catch (RuntimeException e) {
            ui.printLines(List.of(RenderedLine.of(LineKind.ERROR,
                    "✗ 拉取历史失败：" + rootMessage(e))));
        }
    }

    private void onControl(ControlFrame frame) {
        updateState(new UiEvent.ControlArrived(frame, Instant.now()));

        // turn 结束时把未完成的尾巴落地。
        //
        // 正常情况下引擎会发终止事件（映射为 TEXT_END）来收尾，但那是引擎的善意而不是保证：
        // 少了这一步，最后一段没有换行的文本会一直挂在实时尾巴里 ——
        // 交互模式下用户看得见它却永远等不到它变成一行，逐行模式下则完全不可见。
        if (!frame.turnActive()) {
            Transcript.Emission flushed = transcript.get().flush();
            transcript.set(flushed.transcript());
            ui.printLines(flushed.lines());
            ui.setLiveTail("");
        }

        TurnGate gate = turnGate.get();
        if (gate != null) {
            gate.observe(frame);
        }
        refreshStatus();
    }

    private void onStreamError(Throwable error) {
        openGate();
        updateState(new UiEvent.ConnectionChanged(ConnectionState.DISCONNECTED, Instant.now()));
        ui.printLines(List.of(RenderedLine.of(LineKind.ERROR, "✗ 连接中断：" + rootMessage(error))));
        refreshStatus();
    }

    private void updateState(UiEvent event) {
        state.updateAndGet(current -> UiStateReducer.reduce(current, event));
    }

    private void refreshStatus() {
        ui.setStatus(StatusLine.render(state.get(), Instant.now(), ui.width()));
    }

    private String prompt() {
        return "› ";
    }

    /**
     * 一轮回复的完成闸门。
     *
     * <p>只在非交互模式下使用，是一个同步原语而不是业务状态 ——
     * 判定条件必须是「先看到 turnActive=true，再看到 false」：
     * 控制流是快照语义，建连时先到的那一帧本来就是 false，
     * 不区分的话闸门会在 turn 开始之前就被打开。
     */
    private static final class TurnGate {

        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile boolean started;

        void observe(ControlFrame frame) {
            if (frame.turnActive()) {
                started = true;
                return;
            }
            if (started) {
                latch.countDown();
            }
        }

        void forceOpen() {
            latch.countDown();
        }

        boolean await(Duration timeout) throws InterruptedException {
            return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static String newInstructionId() {
        return "i-" + UUID.randomUUID();
    }

    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    /** 订阅句柄的小工具，避免为一个组合订阅引入额外依赖。 */
    private static final class Disposables {

        private Disposables() {
        }

        static Disposable none() {
            return () -> {
            };
        }

        static Disposable composite(Disposable... members) {
            return () -> {
                for (Disposable member : members) {
                    member.dispose();
                }
            };
        }
    }
}
