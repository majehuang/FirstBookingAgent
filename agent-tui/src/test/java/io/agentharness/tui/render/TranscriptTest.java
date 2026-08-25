package io.agentharness.tui.render;

import io.agentharness.protocol.ClientMessage;
import io.agentharness.protocol.MessageRole;
import io.agentharness.protocol.MessageType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptTest {

    private static final Instant AT = Instant.parse("2026-08-23T10:00:00Z");

    @Test
    void 逐字delta先进尾巴_遇换行才落地成行() {
        Transcript.Emission first = Transcript.empty().accept(delta(1, "b1", "你"));
        assertThat(first.lines()).isEmpty();
        assertThat(first.liveTail()).isEqualTo("你");

        Transcript.Emission second = first.transcript().accept(delta(2, "b1", "好\n"));
        assertThat(second.lines()).extracting(RenderedLine::text).containsExactly("你好");
        assertThat(second.liveTail()).isEmpty();
    }

    @Test
    @DisplayName("卡片落地前必须先把未完成的文本尾巴打出去 —— 否则卡片会插进那句话中间")
    void 卡片之前先flush待落地的文本() {
        Transcript transcript = Transcript.empty().accept(delta(1, "b1", "我推荐这几家")).transcript();

        Transcript.Emission emission = transcript.accept(new ClientMessage(
                2, "r1", "b2", MessageRole.ASSISTANT, MessageType.CARD, "3 家酒店",
                Map.of("title", "为你找到 3 家酒店", "items", List.of(), "dataAsOf", "2026-08-23 10:00"), AT));

        assertThat(emission.lines()).extracting(RenderedLine::text)
                .containsExactly("我推荐这几家", "┌ 为你找到 3 家酒店", "└ 数据截至 2026-08-23 10:00");
        assertThat(emission.lines().get(0).kind()).isEqualTo(LineKind.ASSISTANT);
        assertThat(emission.lines().get(1).kind()).isEqualTo(LineKind.CARD);
    }

    @Test
    void 换blockId时旧块的尾巴先落地() {
        Transcript transcript = Transcript.empty().accept(delta(1, "b1", "第一块未完")).transcript();
        Transcript.Emission emission = transcript.accept(delta(2, "b2", "第二块开始"));

        assertThat(emission.lines()).extracting(RenderedLine::text).containsExactly("第一块未完");
        assertThat(emission.liveTail()).isEqualTo("第二块开始");
    }

    @Test
    void TEXT_END把尾巴收尾并清空缓冲() {
        Transcript transcript = Transcript.empty().accept(delta(1, "b1", "收尾")).transcript();
        Transcript.Emission emission = transcript.accept(
                ClientMessage.textEnd(2, "r1", "b1", AT));

        assertThat(emission.lines()).extracting(RenderedLine::text).containsExactly("收尾");
        assertThat(emission.transcript().buffer().hasPending()).isFalse();
    }

    @Test
    void 工具调用渲染出工具名与入参() {
        Transcript.Emission emission = Transcript.empty().accept(new ClientMessage(
                1, "r1", "b1", MessageRole.ASSISTANT, MessageType.TOOL_CALL, "search_hotels",
                Map.of("tool", "search_hotels", "args", Map.of("city", "北京")), AT));

        assertThat(emission.lines()).extracting(RenderedLine::text)
                .containsExactly("⚒ search_hotels(city=北京)");
        assertThat(emission.lines().get(0).kind()).isEqualTo(LineKind.TOOL);
    }

    @Test
    void 错误消息不吃掉已经生成的文本() {
        Transcript transcript = Transcript.empty().accept(delta(1, "b1", "已经写了一半")).transcript();
        Transcript.Emission emission = transcript.accept(
                ClientMessage.error(2, "r1", "下游超时", AT));

        assertThat(emission.lines()).extracting(RenderedLine::text)
                .containsExactly("已经写了一半", "✗ 下游超时");
    }

    @Test
    void clear清空缓冲且不产生任何输出行() {
        Transcript transcript = Transcript.empty().accept(delta(1, "b1", "会被丢掉")).transcript();
        Transcript.Emission emission = transcript.clear();

        assertThat(emission.lines()).isEmpty();
        assertThat(emission.liveTail()).isEmpty();
        assertThat(emission.transcript().buffer().hasPending()).isFalse();
    }

    @Test
    @DisplayName("用户消息由流推回来后渲染 —— 不是本地回显，所以它也在同一个 seq 空间里")
    void 用户消息渲染为提示符行() {
        Transcript.Emission emission = Transcript.empty()
                .accept(ClientMessage.userText(1, "r1", "u-i1", "帮我订酒店", AT));

        assertThat(emission.lines()).singleElement()
                .satisfies(line -> assertThat(line.kind()).isEqualTo(LineKind.USER))
                .satisfies(line -> assertThat(line.text()).isEqualTo("› 帮我订酒店"));
    }

    @Test
    void 用户消息之前先把未完成的助手文本落地() {
        Transcript transcript = Transcript.empty().accept(delta(1, "b1", "上一轮还没说完")).transcript();

        Transcript.Emission emission = transcript.accept(
                ClientMessage.userText(2, "r2", "u-i2", "新问题", AT));

        assertThat(emission.lines()).extracting(RenderedLine::text)
                .containsExactly("上一轮还没说完", "› 新问题");
    }

    private ClientMessage delta(long seq, String blockId, String text) {
        return ClientMessage.textDelta(seq, "r1", blockId, text, AT);
    }
}
