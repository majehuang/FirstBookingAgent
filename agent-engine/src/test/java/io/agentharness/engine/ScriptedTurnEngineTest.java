package io.agentharness.engine;

import io.agentharness.protocol.MessageType;
import io.agentharness.protocol.SessionRef;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试替身自己也要有测试。
 *
 * <p>理由是实打实的：这个引擎最初不发终止事件，于是端到端跑下来稳定地少最后一行 ——
 * 而那是<b>替身不真实</b>造成的假阴性，不是被测代码的问题。
 * 花了不少时间才定位到。替身与真实引擎的行为差异必须被断言锁住。
 */
class ScriptedTurnEngineTest {

    private static final SessionRef SESSION = SessionRef.of("u-1", "s-1");

    private final ScriptedTurnEngine engine = new ScriptedTurnEngine();

    private List<Event> run(String prompt) {
        return engine.stream(SESSION, prompt).collectList().block();
    }

    @Test
    @DisplayName("每轮必须以终止事件收尾 —— 真实引擎会发 AGENT_RESULT，替身不发就会少最后一行")
    void 回复以终止事件收尾() {
        List<Event> events = run("你好");

        assertThat(events).isNotEmpty();
        Event last = events.get(events.size() - 1);
        assertThat(last.getType()).isEqualTo(EventType.AGENT_RESULT);
        assertThat(last.isLast()).isTrue();
        // 映射之后必须是 TEXT_END —— 客户端靠它把最后一段没有换行的文本落地
        assertThat(EventMapper.map(last)).singleElement()
                .satisfies(draft -> assertThat(draft.type()).isEqualTo(MessageType.TEXT_END));
    }

    @Test
    void 回显模式产出可拼回原文的片段() {
        List<Event> events = run("你好世界");

        String assembled = events.stream()
                .filter(e -> e.getType() == EventType.REASONING)
                .flatMap(e -> EventMapper.map(e).stream())
                .map(MessageDraft::text)
                .reduce("", String::concat);

        assertThat(assembled).isEqualTo("收到：你好世界");
    }

    @Test
    void 同一轮的片段共享一个blockId_否则合并不会发生() {
        List<String> blockKeys = run("你好").stream()
                .filter(e -> e.getType() == EventType.REASONING)
                .flatMap(e -> EventMapper.map(e).stream())
                .map(MessageDraft::blockKey)
                .distinct()
                .toList();

        assertThat(blockKeys).hasSize(1);
    }

    @Test
    void burst模式产出指定数量的带序号片段() {
        List<Event> events = run("!burst:50");

        String assembled = events.stream()
                .filter(e -> e.getType() == EventType.REASONING)
                .flatMap(e -> EventMapper.map(e).stream())
                .map(MessageDraft::text)
                .reduce("", String::concat);

        assertThat(assembled).isEqualTo(
                java.util.stream.IntStream.rangeClosed(1, 50)
                        .mapToObj(i -> "[" + i + "]")
                        .reduce("", String::concat));
    }

    @Test
    void error模式先产出内容再失败_用来验证已落库内容会被保留() {
        StepVerifier.create(engine.stream(SESSION, "!error"))
                .expectNextCount(1)
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void empty模式不产出任何事件() {
        assertThat(run("!empty")).isEmpty();
    }

    @Test
    void 记录调用上下文_供断言会话没有串() {
        engine.stream(SESSION, "第一句").blockLast();
        engine.stream(SessionRef.of("u-2", "s-2"), "第二句").blockLast();

        assertThat(engine.calls()).containsExactly(
                new ScriptedTurnEngine.Call("u-1", "s-1", "第一句"),
                new ScriptedTurnEngine.Call("u-2", "s-2", "第二句"));
    }

    @Test
    @DisplayName("!card 剧本的顺序是 推荐理由 → 卡片 → 追问")
    void 卡片剧本的消息顺序() {
        io.agentharness.engine.rich.RichMessageRegistry registry =
                io.agentharness.engine.rich.RichMessageRegistry.of(new CardRendererStub());

        List<MessageDraft> drafts = run("!card").stream()
                .flatMap(e -> EventMapper.map(e, registry).stream())
                .toList();

        List<MessageType> types = drafts.stream().map(MessageDraft::type).toList();
        int cardIndex = types.indexOf(MessageType.CARD);

        assertThat(cardIndex).isGreaterThan(0);
        // 卡片之前是推荐理由，之后是追问 —— 反过来是先甩三个框再解释那三个框是什么
        assertThat(textBefore(drafts, cardIndex)).contains("最合适");
        assertThat(textAfter(drafts, cardIndex)).contains("？");
        assertThat(types).endsWith(MessageType.TEXT_END);
    }

    @Test
    void 卡片剧本抑制了表达型工具的调用状态行() {
        io.agentharness.engine.rich.RichMessageRegistry registry =
                io.agentharness.engine.rich.RichMessageRegistry.of(new CardRendererStub());

        List<MessageType> types = run("!card").stream()
                .flatMap(e -> EventMapper.map(e, registry).stream())
                .map(MessageDraft::type)
                .toList();

        assertThat(types).doesNotContain(MessageType.TOOL_CALL);
        assertThat(types).containsOnlyOnce(MessageType.CARD);
    }

    @Test
    void 卡片剧本的内容固定_才能对着断言逐字节一致() {
        assertThat(run("!card")).hasSameSizeAs(run("!card"));
    }

    private static String textBefore(List<MessageDraft> drafts, int index) {
        return drafts.subList(0, index).stream().map(MessageDraft::text)
                .reduce("", String::concat);
    }

    private static String textAfter(List<MessageDraft> drafts, int index) {
        return drafts.subList(index + 1, drafts.size()).stream().map(MessageDraft::text)
                .reduce("", String::concat);
    }

    /** 最小渲染器，只把 {@code card} 取出来。 */
    private static final class CardRendererStub
            implements io.agentharness.engine.rich.RichMessageRenderer {

        @Override
        public String toolName() {
            return "send_hotel_cards";
        }

        @Override
        @SuppressWarnings("unchecked")
        public MessageDraft render(String blockKey, java.util.Map<String, Object> toolResult) {
            Object card = toolResult.get("card");
            return card instanceof java.util.Map<?, ?> map
                    ? new MessageDraft(blockKey, MessageType.CARD, "3 家酒店",
                            (java.util.Map<String, Object>) map)
                    : null;
        }
    }

    @Test
    void 相同输入产出相同结果() {
        assertThat(run("你好")).hasSameSizeAs(run("你好"));
    }

    @Test
    void 不同轮次的blockId互不相同_否则跨轮文本会被合并() {
        String first = EventMapper.map(run("甲").get(0)).get(0).blockKey();
        String second = EventMapper.map(run("乙").get(0)).get(0).blockKey();

        assertThat(first).isNotEqualTo(second);
    }
}
