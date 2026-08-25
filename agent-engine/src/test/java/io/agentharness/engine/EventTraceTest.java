package io.agentharness.engine;

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖 P2-9 里「turn 中每个 step 的 event」这一环。
 *
 * <p>关键约束是<b>一个事件一行且足够短</b>：追踪的用处是一眼扫过一整轮，
 * 一个 step 刷掉半屏的话这个用处就没了。
 */
class EventTraceTest {

    @Test
    void 文本事件带出片段() {
        assertThat(EventTrace.describe(reasoning(TextBlock.builder().text("为你找到 3 家").build())))
                .isEqualTo("REASONING text=\"为你找到 3 家\"");
    }

    @Test
    @DisplayName("工具调用带出名字与 id —— 这两个才是跨环节对得上的东西")
    void 工具调用带出名字与id() {
        Event event = reasoning(new ToolUseBlock("call-1", "show_hotel_cards",
                Map.of("hotelIds", List.of("h-1"))));

        assertThat(EventTrace.describe(event))
                .isEqualTo("REASONING tool=show_hotel_cards id=call-1");
    }

    @Test
    void 工具结果带出名字与id() {
        Event event = new Event(EventType.TOOL_RESULT,
                msg(MsgRole.TOOL, new ToolResultBlock("call-1", "show_hotel_cards",
                        List.of(TextBlock.builder().text("ok").build()))),
                false);

        assertThat(EventTrace.describe(event)).contains("result=show_hotel_cards", "id=call-1");
    }

    @Test
    @DisplayName("思维链也打 —— 它不进消息表，但排查时正需要看它")
    void 思维链带出片段() {
        assertThat(EventTrace.describe(reasoning(
                ThinkingBlock.builder().thinking("先查价格").build())))
                .isEqualTo("REASONING thinking=\"先查价格\"");
    }

    @Test
    @DisplayName("长文本截断，且整条保持一行")
    void 长文本截断且不换行() {
        String described = EventTrace.describe(reasoning(
                TextBlock.builder().text("很长的一段回复".repeat(20) + "\n换行了").build()));

        assertThat(described).doesNotContain("\n").contains("…");
        assertThat(described).hasSizeLessThan(80);
    }

    @Test
    void 换行被转义而不是真的换行() {
        assertThat(EventTrace.quote("第一行\n第二行")).isEqualTo("\"第一行\\n第二行\"");
    }

    @Test
    void 无消息体的事件只打类型() {
        assertThat(EventTrace.describe(new Event(EventType.AGENT_RESULT, null, false)))
                .isEqualTo("AGENT_RESULT");
    }

    @Test
    void 空事件不抛() {
        assertThat(EventTrace.describe(null)).isEqualTo("null");
    }

    @Test
    void 一个事件多个块全部打出() {
        Event event = reasoning(
                TextBlock.builder().text("这几家最合适").build(),
                new ToolUseBlock("call-2", "show_hotel_cards", Map.of()));

        assertThat(EventTrace.describe(event))
                .isEqualTo("REASONING text=\"这几家最合适\" tool=show_hotel_cards id=call-2");
    }

    @Test
    void 空文本不抛() {
        assertThat(EventTrace.quote(null)).isEqualTo("\"\"");
    }

    private static Event reasoning(ContentBlock... blocks) {
        return new Event(EventType.REASONING, msg(MsgRole.ASSISTANT, blocks), false);
    }

    private static Msg msg(MsgRole role, ContentBlock... blocks) {
        return Msg.builder().id("m-x").role(role).content(List.of(blocks)).build();
    }
}
