package io.agentharness.engine;

import io.agentharness.engine.rich.RichMessageRegistry;
import io.agentharness.engine.rich.RichMessageRenderer;
import io.agentharness.protocol.MessageType;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 表达型工具与普通工具在事件映射上的分叉。 */
class RichMessageMappingTest {

    private static final String CARD_TOOL = "send_cards";

    /** 把 {@code {"card":{...}}} 渲染成一条 CARD 消息的最小渲染器。 */
    private static final RichMessageRenderer RENDERER = new RichMessageRenderer() {
        @Override
        public String toolName() {
            return CARD_TOOL;
        }

        @Override
        @SuppressWarnings("unchecked")
        public MessageDraft render(String blockKey, Map<String, Object> toolResult) {
            Object card = toolResult.get("card");
            return card instanceof Map<?, ?> map
                    ? new MessageDraft(blockKey, MessageType.CARD, "3 家酒店",
                            (Map<String, Object>) map)
                    : null;
        }
    };

    private static final RichMessageRegistry REGISTRY = RichMessageRegistry.of(RENDERER);

    private static Event reasoning(ContentBlock... blocks) {
        return new Event(EventType.REASONING,
                Msg.builder().id("m-1").role(MsgRole.ASSISTANT).content(List.of(blocks)).build(),
                false);
    }

    private static Event toolResult(String toolName, String output) {
        ToolResultBlock block = new ToolResultBlock("call-1", toolName,
                List.of(TextBlock.builder().text(output).build()));
        return new Event(EventType.TOOL_RESULT,
                Msg.builder().id("m-2").role(MsgRole.TOOL).content(block).build(), false);
    }

    @Test
    @DisplayName("表达型工具的调用状态行被抑制 —— 用户该看到卡片，不是「正在调用发卡片的函数」")
    void 表达型工具不产生调用状态行() {
        Event event = reasoning(new ToolUseBlock("call-1", CARD_TOOL, Map.of("ids", List.of("a"))));

        assertThat(EventMapper.map(event, REGISTRY)).isEmpty();
    }

    @Test
    void 普通工具仍然产生调用状态行() {
        Event event = reasoning(new ToolUseBlock("call-1", "search_hotels", Map.of("city", "北京")));

        assertThat(EventMapper.map(event, REGISTRY)).singleElement()
                .satisfies(draft -> assertThat(draft.type()).isEqualTo(MessageType.TOOL_CALL));
    }

    @Test
    void 表达型工具的返回值渲染成卡片() {
        Event event = toolResult(CARD_TOOL,
                "{\"shown\":\"已展示\",\"card\":{\"title\":\"3 家酒店\",\"dataAsOf\":\"2026-08-24\"}}");

        MessageDraft draft = EventMapper.map(event, REGISTRY).get(0);

        assertThat(draft.type()).isEqualTo(MessageType.CARD);
        assertThat(draft.payload()).containsEntry("dataAsOf", "2026-08-24");
        assertThat(draft.text()).isEqualTo("3 家酒店");
    }

    @Test
    @DisplayName("返回值不是合法 JSON 时退回普通工具结果 —— 一条渲染不了的卡片不该中断对话")
    void 渲染失败时降级为工具结果() {
        Event event = toolResult(CARD_TOOL, "这不是 JSON");

        assertThat(EventMapper.map(event, REGISTRY)).singleElement()
                .satisfies(draft -> assertThat(draft.type()).isEqualTo(MessageType.TOOL_RESULT));
    }

    @Test
    void 渲染器返回null时也降级() {
        Event event = toolResult(CARD_TOOL, "{\"shown\":\"只有摘要\"}");

        assertThat(EventMapper.map(event, REGISTRY)).singleElement()
                .satisfies(draft -> assertThat(draft.type()).isEqualTo(MessageType.TOOL_RESULT));
    }

    @Test
    void 没有注册表时一切按普通工具处理() {
        Event call = reasoning(new ToolUseBlock("call-1", CARD_TOOL, Map.of()));
        Event result = toolResult(CARD_TOOL, "{\"card\":{\"title\":\"x\"}}");

        assertThat(EventMapper.map(call)).singleElement()
                .satisfies(draft -> assertThat(draft.type()).isEqualTo(MessageType.TOOL_CALL));
        assertThat(EventMapper.map(result)).singleElement()
                .satisfies(draft -> assertThat(draft.type()).isEqualTo(MessageType.TOOL_RESULT));
    }

    @Test
    @DisplayName("文本与卡片在同一批事件里时保持先后顺序")
    void 文本与表达型工具调用的顺序() {
        Event event = reasoning(
                TextBlock.builder().text("我找到了这几家").build(),
                new ToolUseBlock("call-1", CARD_TOOL, Map.of()));

        // 表达型工具的状态行被抑制，文本原样保留
        assertThat(EventMapper.map(event, REGISTRY)).singleElement()
                .satisfies(draft -> assertThat(draft.text()).isEqualTo("我找到了这几家"));
    }

    @Test
    void 一种富消息只能注册一个渲染器() {
        assertThatThrownBy(() -> RichMessageRegistry.of(RENDERER, RENDERER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("两个渲染器");
    }

    @Test
    void 空注册表不认任何工具() {
        assertThat(RichMessageRegistry.empty().isExpressive(CARD_TOOL)).isFalse();
        assertThat(RichMessageRegistry.empty().forTool(CARD_TOOL)).isEmpty();
        assertThat(RichMessageRegistry.empty().isExpressive(null)).isFalse();
    }

    @Test
    @DisplayName("工具返回的大段 JSON 被压成一行 —— 原样打出来会把对话内容挤没")
    void 工具结果在界面上被压缩() {
        String bigJson = "{\"city\":\"北京\",\"count\":4,\"hotels\":["
                + "{\"id\":\"h-1\",\"name\":\"甲\"},{\"id\":\"h-2\",\"name\":\"乙\"}]}";

        MessageDraft draft = EventMapper.map(toolResult("search_hotels", bigJson)).get(0);

        assertThat(draft.text()).isEqualTo("search_hotels 返回 4 条");
    }

    @Test
    void 有shown摘要时优先用它() {
        assertThat(EventMapper.compact("{\"shown\":\"已展示 3 张卡片\",\"card\":{}}", "t"))
                .isEqualTo("已展示 3 张卡片");
    }

    @Test
    void 非JSON的长结果被截断() {
        String longText = "x".repeat(200);

        assertThat(EventMapper.compact(longText, "t")).hasSize(81).endsWith("…");
    }

    @Test
    void 短文本原样保留_换行压成空格() {
        assertThat(EventMapper.compact("写入成功", "t")).isEqualTo("写入成功");
        assertThat(EventMapper.compact("第一行\n第二行", "t")).isEqualTo("第一行 第二行");
    }

    @Test
    void 压缩只影响展示_富消息渲染读的是原始输出() {
        // 卡片的载荷来自完整 JSON，不受展示压缩影响
        Event event = toolResult(CARD_TOOL,
                "{\"shown\":\"已展示\",\"card\":{\"title\":\"3 家酒店\",\"dataAsOf\":\"2026-08-24\"}}");

        assertThat(EventMapper.map(event, REGISTRY).get(0).payload())
                .containsEntry("title", "3 家酒店")
                .containsEntry("dataAsOf", "2026-08-24");
    }

    @Test
    void 默认引擎不带渲染器() {
        assertThat(new ScriptedTurnEngine().renderers().toolNames()).isEmpty();
        assertThat(ToolBundle.empty().hasToolkit()).isFalse();
    }
}
