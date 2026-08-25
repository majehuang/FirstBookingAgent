package io.agentharness.engine;

import io.agentharness.engine.rich.RichMessageRegistry;
import io.agentharness.protocol.SessionRef;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 *   <li>{@code !card} —— 产出<b>推荐理由 → 卡片 → 追问</b>的完整富消息剧本，
 *       用于在没有模型的情况下端到端验证 CARD 的映射、合批、冻结与重放</li>
 *   <li>其余 —— 回显 {@code 收到：<原文>}，按 2 字一段切开</li>
 * </ul>
 */
public final class ScriptedTurnEngine implements TurnEngine {

    private static final String BURST_PREFIX = "!burst:";
    private static final String CARD_MARKER = "!card";
    private static final String ERROR_MARKER = "!error";
    private static final String EMPTY_MARKER = "!empty";
    private static final int CHUNK_SIZE = 2;

    /** 与 agent-tools 的表达型工具同名。脚本引擎不依赖那个模块，只复用名字。 */
    private static final String CARD_TOOL_NAME = "send_hotel_cards";

    /** 固定的卡片返回值。内容固定，才能对着断言"重开逐字节一致"。 */
    private static final String CARD_RESULT_JSON =
            "{\"shown\":\"已向用户展示 3 张酒店卡片：北京国贸大酒店、王府井希尔顿、东直门亚朵\","
            + "\"card\":{\"title\":\"为你找到 3 家酒店\",\"dataAsOf\":\"2026-08-24 10:00\","
            + "\"items\":["
            + "{\"id\":\"h-guomao\",\"name\":\"北京国贸大酒店\",\"note\":\"含双早\","
            + "\"price\":\"¥1,280\",\"rating\":\"4.8★\"},"
            + "{\"id\":\"h-hilton\",\"name\":\"王府井希尔顿\",\"note\":\"步行 12 分钟\","
            + "\"price\":\"¥1,050\",\"rating\":\"4.7★\"},"
            + "{\"id\":\"h-atour\",\"name\":\"东直门亚朵\",\"note\":\"性价比高\","
            + "\"price\":\"¥680\",\"rating\":\"4.6★\"}]}}";

    private final ConcurrentLinkedQueue<Call> calls = new ConcurrentLinkedQueue<>();
    private final AtomicLong messageCounter = new AtomicLong();
    private final RichMessageRegistry renderers;

    public ScriptedTurnEngine() {
        this(RichMessageRegistry.empty());
    }

    /**
     * 带渲染器的可控引擎。
     *
     * <p>{@code !card} 剧本伪造的是工具<b>调用与结果</b>，渲染仍走真实的注册表 ——
     * 这样端到端跑出来的卡片与真实模型走出来的是同一条渲染路径，
     * 否则测试验的就是另一套代码了。
     */
    public ScriptedTurnEngine(RichMessageRegistry renderers) {
        this.renderers = renderers == null ? RichMessageRegistry.empty() : renderers;
    }

    @Override
    public RichMessageRegistry renderers() {
        return renderers;
    }

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
        if (prompt.startsWith(CARD_MARKER)) {
            return cardScript(messageId);
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
     * 富消息剧本：<b>推荐理由 → 卡片 → 追问</b>。
     *
     * <p>顺序不是随意的。先给理由再给卡片，用户读到卡片时已经知道该看什么；
     * 反过来是先甩三个框、再解释刚才那三个框是什么意思。这是 G2 的验收项。
     *
     * <p>这里<b>伪造</b>工具调用与结果，不真的执行工具 —— 目的是让 CARD 的映射、合批、
     * 冻结落库、重放这整条链路能在没有模型、也没有业务数据源的情况下被端到端断言。
     */
    private Flux<Event> cardScript(String messageId) {
        String toolCallId = "call-" + messageCounter.get();

        Msg call = Msg.builder().id(messageId).role(MsgRole.ASSISTANT)
                .content(new ToolUseBlock(toolCallId, CARD_TOOL_NAME,
                        Map.of("title", "为你找到 3 家酒店",
                                "hotelIds", List.of("h-guomao", "h-hilton", "h-atour"))))
                .build();

        Msg result = Msg.builder().id(messageId + "-r").role(MsgRole.TOOL)
                .content(new ToolResultBlock(toolCallId, CARD_TOOL_NAME,
                        List.of(TextBlock.builder().text(CARD_RESULT_JSON).build())))
                .build();

        return Flux.concat(
                Flux.fromIterable(chunk("这几家最合适：国贸含双早且离会场最近。\n"))
                        .map(piece -> textEvent(messageId, piece)),
                Flux.just(new Event(EventType.REASONING, call, false)),
                Flux.just(new Event(EventType.TOOL_RESULT, result, false)),
                Flux.fromIterable(chunk("要我直接锁一间吗？"))
                        .map(piece -> textEvent(messageId + "-tail", piece)),
                finish(messageId));
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
