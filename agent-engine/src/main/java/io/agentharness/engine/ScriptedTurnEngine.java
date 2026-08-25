package io.agentharness.engine;

import io.agentharness.protocol.SessionRef;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 可控引擎：给定输入必定产出同一串事件。
 *
 * <p>存在的理由是<b>让端到端测试不依赖模型</b>。真实模型的输出既不确定又要花钱，
 * 用它来验证"1000 个片段是否保持顺序"这种事既慢又会偶发失败 ——
 * 而那正是最需要稳定复现的一类断言。
 *
 * <p>输入里的指令前缀改变行为：
 * <ul>
 *   <li>{@code !burst:N} —— 产出 N 个带序号的片段，用于顺序与合批验证</li>
 *   <li>{@code !error} —— 先产出一个片段再抛异常，用于 turn 失败路径</li>
 *   <li>{@code !empty} —— 不产出任何事件</li>
 *   <li>其余 —— 回显 {@code 收到：<原文>}，按 2 字一段切开</li>
 * </ul>
 */
public final class ScriptedTurnEngine implements TurnEngine {

    private static final String BURST_PREFIX = "!burst:";
    private static final String ERROR_MARKER = "!error";
    private static final String EMPTY_MARKER = "!empty";
    private static final int CHUNK_SIZE = 2;

    private final ConcurrentLinkedQueue<Call> calls = new ConcurrentLinkedQueue<>();
    private final AtomicLong messageCounter = new AtomicLong();

    /** 一次调用的记录，供测试断言上下文没有串会话。 */
    public record Call(String userId, String sessionId, String text) {
    }

    @Override
    public Flux<Event> stream(SessionRef session, String text) {
        calls.add(new Call(session.userId(), session.sessionId(), text));
        String messageId = "scripted-" + messageCounter.incrementAndGet();
        String prompt = text == null ? "" : text;

        if (prompt.startsWith(EMPTY_MARKER)) {
            return Flux.empty();
        }
        if (prompt.startsWith(ERROR_MARKER)) {
            return Flux.concat(
                    Flux.just(textEvent(messageId, "开始处理")),
                    Flux.error(new IllegalStateException("脚本化引擎按要求失败")));
        }
        if (prompt.startsWith(BURST_PREFIX)) {
            return burst(messageId, parseBurstCount(prompt)).concatWith(finish(messageId));
        }
        return Flux.fromIterable(chunk("收到：" + prompt))
                .map(piece -> textEvent(messageId, piece))
                .concatWith(finish(messageId));
    }

    /** 每个片段带自己的序号，拼起来能逐字节校验顺序。 */
    private Flux<Event> burst(String messageId, int count) {
        return Flux.range(1, count).map(index -> textEvent(messageId, "[" + index + "]"));
    }

    private static int parseBurstCount(String prompt) {
        try {
            return Math.max(1, Integer.parseInt(prompt.substring(BURST_PREFIX.length()).strip()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * 终止事件。
     *
     * <p>真实引擎每轮结束都会发 {@code AGENT_RESULT}，映射后是一条 {@code TEXT_END} ——
     * 客户端靠它把最后一段没有换行的文本落地。脚本引擎不发的话，
     * 端到端测试会稳定地少最后一行，而那是<b>测试替身不真实</b>造成的假阴性。
     */
    private static Flux<Event> finish(String messageId) {
        Msg message = Msg.builder()
                .id(messageId)
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text("").build())
                .build();
        return Flux.just(new Event(EventType.AGENT_RESULT, message, true));
    }

    private static Event textEvent(String messageId, String text) {
        Msg message = Msg.builder()
                .id(messageId)
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
        return new Event(EventType.REASONING, message, false);
    }

    static List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += CHUNK_SIZE) {
            chunks.add(text.substring(i, Math.min(text.length(), i + CHUNK_SIZE)));
        }
        return List.copyOf(chunks);
    }

    /** 调用记录，按发生顺序。 */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    @Override
    public void interrupt() {
        // 脚本化引擎的每一步都是即时的，没有可打断的中间态
    }

    @Override
    public void close() {
        calls.clear();
    }
}
